import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

const EXPECTED_EXTENSION_ID = 'daijhhcaecladkkpcjdlfgcokohehhmn'
const manifest = JSON.parse(await readFile(new URL('../public/manifest.json', import.meta.url), 'utf8'))

assert.equal(typeof manifest.key, 'string', 'manifest key must be present to keep the extension ID stable')
assert.ok(
  manifest.host_permissions?.includes('https://app.example.com/*'),
  'manifest host_permissions must include the production API origin',
)
assert.ok(
  manifest.web_accessible_resources?.some(entry => (
    entry.resources?.includes('character-assets/*')
      && entry.matches?.includes('http://*/*')
      && entry.matches?.includes('https://*/*')
  )),
  'content script sprite assets must be web-accessible on HTTP(S) pages',
)

const publicKey = Buffer.from(manifest.key, 'base64')
const digest = createHash('sha256').update(publicKey).digest().subarray(0, 16)
const extensionId = [...digest]
  .map(byte => String.fromCharCode(97 + (byte >> 4), 97 + (byte & 0x0f)))
  .join('')

assert.equal(extensionId, EXPECTED_EXTENSION_ID)
console.log('extension manifest contract test passed')
