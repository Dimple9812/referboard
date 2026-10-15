// Builds dist/referboard-extension.zip for the Chrome Web Store: only the files the extension needs.
// The Web Store assigns its own key, so the development "key" is removed from the packaged manifest.
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/referboard-extension', { recursive: true });
for (const dir of ['src', 'icons', 'fonts']) cpSync(dir, `dist/referboard-extension/${dir}`, { recursive: true });
const manifest = JSON.parse(readFileSync('manifest.json', 'utf8'));
delete manifest.key;
writeFileSync('dist/referboard-extension/manifest.json', JSON.stringify(manifest, null, 2));
execFileSync('zip', ['-qr', '../referboard-extension.zip', '.'], { cwd: 'dist/referboard-extension' });
console.log('dist/referboard-extension.zip');
