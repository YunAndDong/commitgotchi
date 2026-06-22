import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import { board, codex, gameState, resetGameState } from '../src/stores/game.js'

const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47])
const defaultSpriteUrl = '/character-assets/default_image1.png'

const defaultSpriteBytes = await readFile(new URL('../../docs/default_image1.png', import.meta.url))
assert.deepEqual(defaultSpriteBytes.subarray(0, 4), pngSignature, 'default_image1.png is available as a PNG sprite sheet')

resetGameState()
assert.equal(gameState.characters[0].spriteSheetUrl, defaultSpriteUrl)
assert.equal(gameState.characters[1].spriteSheetUrl, defaultSpriteUrl)
assert.equal(gameState.characters[0].spriteMeta.columns, 3)
assert.equal(gameState.characters[0].spriteMeta.rows, 1)
assert.deepEqual(gameState.characters[0].spriteMeta.frameMap.joy, [0, 0])
assert.deepEqual(gameState.characters[1].spriteMeta.frameMap.sad, [0, 1])
assert.equal(codex()[0].spriteSheetUrl, defaultSpriteUrl)
assert.equal(board()[0].spriteSheetUrl, defaultSpriteUrl)

const component = await readFile(new URL('../src/components/CgSprite.vue', import.meta.url), 'utf8')
assert.ok(component.includes('spriteSheetUrl'), 'CgSprite accepts sprite sheet URLs')
assert.ok(component.includes('backgroundPosition'), 'CgSprite slices a selected frame from the sheet')
assert.ok(component.includes('spr__sheet'), 'CgSprite renders a sheet frame before SVG fallback')

console.log('default sprite asset contract test passed')
