#!/usr/bin/env node
// Publication de la version PUBLIQUE de Prisme sur GitHub (page « Releases » du dépôt public).
// Les TV publiques lisent https://github.com/<dépôt>/releases/latest/download/prisme-public.json
// et s'installent toutes seules la nouvelle version.
//
//   node tools/publish-update.mjs <Prisme.apk> <versionCode> <versionName> ["notes de version"]
//   node tools/publish-update.mjs --replace <Prisme.apk> <versionCode> <versionName>   (remplace les fichiers d'une
//                                                                                  publication existante)
//
// Prérequis (une fois) : GitHub CLI installé et connecté (gh auth login), dépôt défini par update.repo
// dans secrets.properties (ex. update.repo=nickZambrano/prisme).
// Garde-fou : lit le type inscrit DANS l'APK (meta-data prisme.flavour) et refuse tout ce qui n'est pas « public »
// (l'ancienne version personnelle contient les identifiants IPTV).
// Le fichier de version garde le nom prisme-public.json : c'est l'adresse que lisent les versions déjà installées.

import { readFileSync, writeFileSync, existsSync, mkdtempSync, copyFileSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir, homedir } from "node:os";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const die = (msg) => { console.error("Erreur : " + msg); process.exit(1); };

function repo() {
  const txt = readFileSync(join(ROOT, "secrets.properties"), "utf8");
  const m = txt.match(/^\s*update\.repo\s*=\s*([A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+)\s*$/m);
  if (!m) die("update.repo manquant dans secrets.properties");
  return m[1];
}

/** Type et version inscrits dans l'APK (aapt2 du SDK installé dans ~/.androidtv-build). */
function apkInfo(apk) {
  const bt = join(homedir(), ".androidtv-build", "sdk", "build-tools");
  const ver = readdirSync(bt).sort().pop();
  const aapt = join(bt, ver, process.platform === "win32" ? "aapt2.exe" : "aapt2");
  const xml = execFileSync(aapt, ["dump", "xmltree", apk, "--file", "AndroidManifest.xml"], { encoding: "utf8" });
  const i = xml.indexOf("prisme.flavour");
  const flavour = i < 0 ? null : (xml.slice(i, i + 300).match(/"(public|personal)"/) || [])[1];
  const badging = execFileSync(aapt, ["dump", "badging", apk], { encoding: "utf8" });
  const code = Number((badging.match(/versionCode='(\d+)'/) || [])[1]);
  const name = (badging.match(/versionName='([^']*)'/) || [])[1];
  return { flavour, code, name };
}

const argv = process.argv.slice(2);
const replace = argv[0] === "--replace";
const [apk, versionCode, versionName, notes] = replace ? argv.slice(1) : argv;
if (!apk || !versionCode || !versionName) die("usage : publish-update.mjs [--replace] <Prisme.apk> <versionCode> <versionName> [notes]");
if (!existsSync(apk)) die("APK introuvable : " + apk);
const code = Number(versionCode);
if (!Number.isInteger(code) || code <= 0) die("versionCode invalide");
const info = apkInfo(apk);
if (info.flavour !== "public") die(`cet APK est de type « ${info.flavour ?? "inconnu"} » : seule la version PUBLIQUE peut être publiée`);
if (info.code !== code || info.name !== versionName) die(`l'APK est la version ${info.name} (${info.code}), pas ${versionName} (${code})`);

const r = repo();
const tag = "v" + versionName;
const bytes = readFileSync(apk);
const sha256 = createHash("sha256").update(bytes).digest("hex");
const dir = mkdtempSync(join(tmpdir(), "prisme-release-"));
const apkOut = join(dir, "Prisme.apk");
copyFileSync(apk, apkOut);
const manifest = {
  versionCode: code,
  versionName,
  // Adresse propre à CETTE version (l'APK « latest » pourrait changer entre la lecture du fichier et le téléchargement).
  apk: `https://github.com/${r}/releases/download/${tag}/Prisme.apk`,
  sha256,
  size: bytes.length,
  flavour: "public",
};
const manifestOut = join(dir, "prisme-public.json");
writeFileSync(manifestOut, JSON.stringify(manifest, null, 2));

/** Nouveautés de cette version, lues dans le journal livré avec l'appli (app/src/main/assets/changelog.txt). */
function changelogNotes(version) {
  const txt = readFileSync(join(ROOT, "app", "src", "main", "assets", "changelog.txt"), "utf8");
  const lines = txt.split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim() === "## " + version);
  if (start < 0) return null;
  const items = [];
  for (const l of lines.slice(start + 1)) {
    if (l.startsWith("## ")) break;
    if (l.trim().startsWith("- ")) items.push(l.trim());
  }
  return items.length ? "## Nouveautés\n\n" + items.join("\n") : null;
}
const fromChangelog = changelogNotes(versionName);
if (!notes && !fromChangelog) die(`aucune entrée « ## ${versionName} » dans changelog.txt : ajoute les nouveautés avant de publier`);
const body = (notes || fromChangelog) +
  "\n\nInstallation : télécharger Prisme.apk sur la TV (ou l'envoyer depuis le téléphone). " +
  "Les versions suivantes s'installent ensuite toutes seules.";
if (replace) {
  console.log(`Remplacement des fichiers de Prisme ${versionName} sur ${r}…`);
  // APK d'abord, fichier de version ensuite (une TV ne voit la nouvelle adresse qu'une fois l'APK en place).
  execFileSync("gh", ["release", "upload", tag, apkOut, "--clobber", "--repo", r], { stdio: "inherit" });
  execFileSync("gh", ["release", "upload", tag, manifestOut, "--clobber", "--repo", r], { stdio: "inherit" });
} else {
  console.log(`Publication de Prisme ${versionName} (${code}) sur ${r}…`);
  execFileSync("gh", [
    "release", "create", tag, apkOut, manifestOut,
    "--repo", r, "--title", `Prisme ${versionName}`, "--notes", body, "--latest",
  ], { stdio: "inherit" });
}
console.log(`Publié : https://github.com/${r}/releases/tag/${tag}`);
