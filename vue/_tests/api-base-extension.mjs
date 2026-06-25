globalThis.location = { protocol: 'chrome-extension:' }

const { apiAssetUrl } = await import('../src/api/client.js?extension-api-base')

const expected = 'http://localhost:8080/character-assets/default_image1.png'
const actual = apiAssetUrl('/character-assets/default_image1.png')

if (actual !== expected) {
  console.error(`  ✗ extension API base fallback: expected ${expected}, got ${actual}`)
  process.exit(1)
}

console.log('extension API base fallback test passed')
