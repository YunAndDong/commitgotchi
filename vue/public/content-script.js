/*
 * Standalone MV3 content script. It intentionally does not import the Vue app:
 * visited pages only receive this small Shadow DOM renderer.
 */
(() => {
  const STORAGE_KEY = 'commitgotchi.activeGotchi'
  const VISIBILITY_STORAGE_KEY = 'commitgotchi.gotchiVisible'
  const WALK_CYCLE_MS = 24_000
  const WALKER_WIDTH = 88
  const EDGE_PADDING = 12
  let host = null
  let shadow = null
  let hostObserver = null
  let currentGotchi = null
  let walker = null
  let sprite = null
  let positionFrame = null
  let gotchiVisible = true
  let activeRevision = 0
  let visibilityRevision = 0

  function validGotchi(value) {
    return value && (typeof value.id === 'string' || typeof value.id === 'number')
      && typeof value.name === 'string' && value.name.trim()
      && ['joy', 'sad', 'angry'].includes(value.emotion)
      && typeof value.isEvolved === 'boolean'
  }

  function normalizeSpriteMeta(raw) {
    if (!raw) return null
    if (typeof raw === 'string') {
      try { return normalizeSpriteMeta(JSON.parse(raw)) } catch { return null }
    }
    return typeof raw === 'object' ? raw : null
  }

  function bundledCharacterAssetUrl(value) {
    const assetMarker = '/character-assets/'
    const markerIndex = value.indexOf(assetMarker)
    const path = markerIndex >= 0
      ? value.slice(markerIndex + 1)
      : value.replace(/^\.?\//, '')
    if (!path.startsWith('character-assets/')) return ''
    return chrome.runtime?.getURL ? chrome.runtime.getURL(path) : ''
  }

  function resolveSpriteUrl(raw) {
    if (typeof raw !== 'string') return ''
    const value = raw.trim()
    if (!value) return ''
    const bundledUrl = bundledCharacterAssetUrl(value)
    if (bundledUrl) return bundledUrl
    if (/^[a-z][a-z\d+\-.]*:/i.test(value) || value.startsWith('//')) return value
    if (chrome.runtime?.getURL) {
      return chrome.runtime.getURL(value.replace(/^\.?\//, ''))
    }
    return value
  }

  function frameStyle(gotchi) {
    if (gotchi.imageStatus === 'PENDING') return null
    const spriteMeta = normalizeSpriteMeta(gotchi.spriteMeta)
    const spriteSheetUrl = resolveSpriteUrl(gotchi.spriteSheetUrl)
    if (!spriteMeta || !spriteSheetUrl) return null

    const columns = Math.max(1, Number(spriteMeta.columns) || 3)
    const rows = Math.max(1, Number(spriteMeta.rows) || 1)
    const map = spriteMeta.frameMap || {}
    let coords = Array.isArray(map[gotchi.emotion]) ? map[gotchi.emotion] : null
    if (!coords && gotchi.emotion === 'joy' && Array.isArray(map.happy)) coords = map.happy
    if (!coords) {
      const stage = gotchi.isEvolved ? 'mature' : 'baby'
      const stageMap = map[stage] || {}
      const key = gotchi.emotion === 'joy' && stageMap.happy && !stageMap.joy ? 'happy' : gotchi.emotion
      coords = Array.isArray(stageMap[key]) ? stageMap[key] : [0, 0]
    }

    const row = Math.min(rows - 1, Math.max(0, Number(coords[0]) || 0))
    const column = Math.min(columns - 1, Math.max(0, Number(coords[1]) || 0))
    return {
      spriteSheetUrl,
      backgroundSize: `${columns * 100}% ${rows * 100}%`,
      backgroundPosition: `${columns <= 1 ? 0 : (column / (columns - 1)) * 100}% ${rows <= 1 ? 0 : (row / (rows - 1)) * 100}%`,
    }
  }

  function removeHost() {
    if (positionFrame !== null) globalThis.cancelAnimationFrame?.(positionFrame)
    hostObserver?.disconnect()
    host?.remove()
    host = null
    shadow = null
    hostObserver = null
    walker = null
    sprite = null
    positionFrame = null
  }

  function removeGotchi() {
    currentGotchi = null
    removeHost()
  }

  function ensureHost() {
    if (!host) {
      host = document.createElement('div')
      host.setAttribute('aria-hidden', 'true')
      for (const [property, value] of Object.entries({
        all: 'initial',
        position: 'fixed',
        inset: '0',
        'z-index': '2147483647',
        display: 'block',
        overflow: 'hidden',
        'pointer-events': 'none',
        contain: 'strict',
      })) {
        host.style.setProperty(property, value, 'important')
      }
      shadow = host.attachShadow({ mode: 'closed' })
      hostObserver = new MutationObserver(() => {
        if (currentGotchi && host && !host.isConnected) document.documentElement.append(host)
      })
      hostObserver.observe(document.documentElement, { childList: true })
    }
    if (!host.isConnected) document.documentElement.append(host)
  }

  function faceMarkup(emotion, dark) {
    if (emotion === 'sad') {
      return `
        <circle cx="26" cy="34" r="2.4" fill="${dark}"/>
        <circle cx="38" cy="34" r="2.4" fill="${dark}"/>
        <path d="M40 39q2 2 3 5" stroke="#5f97d6" stroke-width="2" fill="none" stroke-linecap="round"/>
        <path d="M27 45q5-4 10 0" stroke="${dark}" stroke-width="2.5" fill="none" stroke-linecap="round"/>
      `
    }
    if (emotion === 'angry') {
      return `
        <line x1="23" y1="31" x2="29" y2="34" stroke="${dark}" stroke-width="2.5" stroke-linecap="round"/>
        <line x1="41" y1="31" x2="35" y2="34" stroke="${dark}" stroke-width="2.5" stroke-linecap="round"/>
        <circle cx="26" cy="35" r="2" fill="${dark}"/>
        <circle cx="38" cy="35" r="2" fill="${dark}"/>
        <path d="M27 44h10" stroke="${dark}" stroke-width="2.5" fill="none" stroke-linecap="round"/>
      `
    }
    return `
      <path d="M23 33q3-4 6 0M35 33q3-4 6 0" stroke="${dark}" stroke-width="2.5" fill="none" stroke-linecap="round"/>
      <path d="M26 42q6 6 12 0" stroke="${dark}" stroke-width="2.5" fill="none" stroke-linecap="round"/>
    `
  }

  function spriteMarkup(gotchi) {
    const dark = '#2F5E34'
    const aura = gotchi.isEvolved
      ? '<circle cx="32" cy="34" r="26" fill="#fff7d6" opacity=".65"/>'
      : ''
    const flower = gotchi.isEvolved
      ? '<ellipse cx="32" cy="3" rx="5" ry="3.5" fill="#eab43f"/>'
      : ''
    return `
      <svg viewBox="0 0 64 64" shape-rendering="crispEdges" aria-hidden="true">
        ${aura}
        <rect x="30" y="8" width="4" height="9" fill="${dark}"/>
        <ellipse cx="26" cy="9" rx="7" ry="4" fill="#54A857"/>
        <ellipse cx="39" cy="7" rx="8" ry="4.5" fill="#54A857"/>
        ${flower}
        <circle cx="32" cy="36" r="${gotchi.isEvolved ? 22 : 18}" fill="#7FC86A" stroke="${dark}" stroke-width="2.5"/>
        <circle cx="22" cy="40" r="3" fill="#f3a6a0"/>
        <circle cx="42" cy="40" r="3" fill="#f3a6a0"/>
        ${faceMarkup(gotchi.emotion, dark)}
      </svg>
    `
  }

  function fallbackSpriteContent(gotchi) {
    const fallback = document.createElement('div')
    fallback.className = 'sprite-fallback'
    fallback.innerHTML = spriteMarkup(gotchi)
    return fallback
  }

  function replaceWithFallback(frame, gotchi) {
    if (frame.__commitGotchiBroken) return
    frame.__commitGotchiBroken = true
    const fallback = fallbackSpriteContent(gotchi)
    if (typeof frame.replaceWith === 'function') {
      frame.replaceWith(fallback)
      return
    }
    frame.className = fallback.className
    frame.innerHTML = fallback.innerHTML
  }

  function spriteContent(gotchi) {
    const style = frameStyle(gotchi)
    if (!style) return fallbackSpriteContent(gotchi)

    const frame = document.createElement('div')
    frame.className = 'sprite-frame'
    frame.style.setProperty('background-image', `url("${style.spriteSheetUrl.replace(/"/g, '\\"')}")`)
    frame.style.setProperty('background-size', style.backgroundSize)
    frame.style.setProperty('background-position', style.backgroundPosition)
    const probe = document.createElement('img')
    probe.className = 'sprite-probe'
    probe.alt = ''
    probe.setAttribute('aria-hidden', 'true')
    probe.addEventListener?.('error', () => { replaceWithFallback(frame, gotchi) }, { once: true })
    probe.src = style.spriteSheetUrl
    frame.append(probe)
    return frame
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value))
  }

  function smoothstep(value) {
    return value * value * (3 - (2 * value))
  }

  function hashGotchi(gotchi) {
    const source = `${gotchi?.id ?? ''}|${gotchi?.name ?? ''}`
    let hash = 2166136261
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index)
      hash = Math.imul(hash, 16777619)
    }
    return hash >>> 0
  }

  function seededUnit(seed, salt) {
    let value = (seed + Math.imul(salt, 374761393)) >>> 0
    value = Math.imul(value ^ (value >>> 15), 2246822519)
    value = Math.imul(value ^ (value >>> 13), 3266489917)
    return ((value ^ (value >>> 16)) >>> 0) / 4294967295
  }

  function motionProfile(gotchi) {
    const seed = hashGotchi(gotchi)
    return {
      seed,
      cycleMs: 18_000 + (seededUnit(seed, 1) * 9_000),
      phaseOffset: seededUnit(seed, 2),
      pauseRatio: 0.06 + (seededUnit(seed, 3) * 0.07),
      wobbleRatio: 0.012 + (seededUnit(seed, 4) * 0.018),
      bobPixels: 1.5 + (seededUnit(seed, 5) * 2.5),
      stepMs: 360 + (seededUnit(seed, 6) * 180),
    }
  }

  function travelBounds() {
    const viewportWidth = Math.max(0, Number(globalThis.innerWidth) || 0)
    const walkerWidth = Math.min(WALKER_WIDTH, Math.max(0, viewportWidth - (EDGE_PADDING * 2)))
    const minX = viewportWidth <= walkerWidth ? 0 : Math.min(EDGE_PADDING, viewportWidth - walkerWidth)
    const maxX = Math.max(minX, viewportWidth - walkerWidth - minX)
    return { minX, maxX }
  }

  function sharedMotion(now, gotchi) {
    const profile = motionProfile(gotchi)
    const phase = ((now / profile.cycleMs) + profile.phaseOffset) % 1
    const local = phase < 0.5 ? phase / 0.5 : (phase - 0.5) / 0.5
    const pause = profile.pauseRatio
    const walking = local > pause && local < (1 - pause)
    const stride = walking ? clamp((local - pause) / (1 - (pause * 2)), 0, 1) : (local <= pause ? 0 : 1)
    const eased = smoothstep(stride)
    const routeProgress = phase < 0.5 ? eased : 1 - eased
    const edgeFade = Math.sin(Math.PI * eased)
    const wobble = Math.sin((now / 2300) + profile.seed) * profile.wobbleRatio * edgeFade
    const progress = clamp(routeProgress + wobble, 0, 1)
    const { minX, maxX } = travelBounds()
    const x = minX + ((maxX - minX) * progress)
    const step = walking ? Math.sin((now / profile.stepMs) * Math.PI * 2) : 0
    return {
      x: clamp(x, minX, maxX),
      y: -Math.max(0, step) * profile.bobPixels,
      squash: walking ? 1 - (Math.abs(step) * 0.025) : 1,
    }
  }

  function syncSharedPosition() {
    if (!currentGotchi || !walker || !sprite) {
      positionFrame = null
      return
    }

    if (globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      const { minX } = travelBounds()
      walker.style.transform = `translateX(${minX}px)`
      sprite.style.transform = 'translateY(0px)'
    } else {
      const { x, y, squash } = sharedMotion(Date.now(), currentGotchi)
      walker.style.transform = `translateX(${x}px)`
      sprite.style.transform = `translateY(${y}px) scaleY(${squash})`
    }

    positionFrame = globalThis.requestAnimationFrame?.(syncSharedPosition) ?? null
  }

  function startPositionSync() {
    if (positionFrame !== null) globalThis.cancelAnimationFrame?.(positionFrame)
    syncSharedPosition()
  }

  function renderGotchi(gotchi) {
    if (!validGotchi(gotchi)) {
      removeGotchi()
      return
    }

    currentGotchi = gotchi
    if (!gotchiVisible) {
      removeHost()
      return
    }

    ensureHost()
    shadow.replaceChildren()

    const style = document.createElement('style')
    style.textContent = `
      :host {
        all: initial;
        position: fixed;
        inset: 0;
        z-index: 2147483647;
        display: block;
        overflow: hidden;
        pointer-events: none !important;
        contain: strict;
      }
      *, *::before, *::after { box-sizing: border-box; pointer-events: none !important; }
      .walker {
        position: absolute;
        left: 0;
        bottom: max(8px, env(safe-area-inset-bottom));
        width: min(88px, max(0px, calc(100vw - 24px)));
        display: flex;
        flex-direction: column;
        align-items: center;
        will-change: transform;
      }
      .sprite {
        width: 72px;
        height: 72px;
        transform-origin: center bottom;
        will-change: transform;
      }
      .sprite-frame,
      .sprite-fallback,
      svg {
        display: block;
        width: 72px;
        height: 72px;
        filter: drop-shadow(0 2px 1px rgba(24, 32, 24, .25));
      }
      .sprite-frame {
        position: relative;
        overflow: hidden;
        background-repeat: no-repeat;
        image-rendering: pixelated;
        image-rendering: crisp-edges;
      }
      .sprite-probe {
        position: absolute;
        width: 1px;
        height: 1px;
        opacity: 0;
        pointer-events: none;
      }
      .ground { width: 48px; height: 7px; margin-top: -4px; border-radius: 50%; background: rgba(24, 32, 24, .35); filter: blur(1px); }
      .name {
        max-width: 88px;
        margin-top: 4px;
        padding: 2px 7px;
        overflow: hidden;
        border: 1px solid rgba(47, 94, 52, .45);
        border-radius: 999px;
        background: rgba(255, 255, 255, .88);
        color: #26352a;
        font: 700 11px/1.3 system-ui, sans-serif;
        text-overflow: ellipsis;
        white-space: nowrap;
        box-shadow: 0 1px 3px rgba(24, 32, 24, .15);
      }
    `

    walker = document.createElement('div')
    walker.className = 'walker'
    sprite = document.createElement('div')
    sprite.className = 'sprite'
    sprite.append(spriteContent(gotchi))
    const ground = document.createElement('div')
    ground.className = 'ground'
    const name = document.createElement('div')
    name.className = 'name'
    name.textContent = gotchi.name
    walker.append(sprite, ground, name)
    shadow.append(style, walker)
    startPositionSync()
  }

  const initialActiveRevision = activeRevision
  const initialVisibilityRevision = visibilityRevision
  chrome.storage.local.get([STORAGE_KEY, VISIBILITY_STORAGE_KEY])
    .then(result => {
      if (visibilityRevision === initialVisibilityRevision) {
        gotchiVisible = result[VISIBILITY_STORAGE_KEY] !== false
      }
      if (activeRevision === initialActiveRevision) {
        renderGotchi(result[STORAGE_KEY])
      }
    })
    .catch(() => {
      if (activeRevision === initialActiveRevision) removeGotchi()
    })

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === 'local' && Object.prototype.hasOwnProperty.call(changes, STORAGE_KEY)) {
      activeRevision++
      renderGotchi(changes[STORAGE_KEY].newValue)
    }
    if (areaName === 'local' && Object.prototype.hasOwnProperty.call(changes, VISIBILITY_STORAGE_KEY)) {
      visibilityRevision++
      gotchiVisible = changes[VISIBILITY_STORAGE_KEY].newValue !== false
      if (gotchiVisible) renderGotchi(currentGotchi)
      else removeHost()
    }
  })
})()
