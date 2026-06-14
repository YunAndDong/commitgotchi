/*
 * Standalone MV3 content script. It intentionally does not import the Vue app:
 * visited pages only receive this small Shadow DOM renderer.
 */
(() => {
  const STORAGE_KEY = 'commitgotchi.activeGotchi'
  let host = null
  let shadow = null
  let hostObserver = null
  let currentGotchi = null
  let changeRevision = 0

  function validGotchi(value) {
    return value && (typeof value.id === 'string' || typeof value.id === 'number')
      && typeof value.name === 'string' && value.name.trim()
      && ['joy', 'sad', 'angry'].includes(value.emotion)
      && typeof value.isEvolved === 'boolean'
  }

  function removeGotchi() {
    currentGotchi = null
    hostObserver?.disconnect()
    host?.remove()
    host = null
    shadow = null
    hostObserver = null
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

  function renderGotchi(gotchi) {
    if (!validGotchi(gotchi)) {
      removeGotchi()
      return
    }

    currentGotchi = gotchi
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
        left: 12px;
        bottom: max(8px, env(safe-area-inset-bottom));
        width: 88px;
        display: flex;
        flex-direction: column;
        align-items: center;
        animation: commitgotchi-stroll 24s linear infinite;
        will-change: transform;
      }
      .sprite {
        width: 72px;
        height: 72px;
        transform-origin: center bottom;
        animation: commitgotchi-face 24s steps(1, end) infinite;
      }
      svg { display: block; width: 72px; height: 72px; filter: drop-shadow(0 2px 1px rgba(24, 32, 24, .25)); }
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
      @keyframes commitgotchi-stroll {
        0% { transform: translateX(-88px); }
        49% { transform: translateX(calc(100vw - 112px)); }
        50% { transform: translateX(calc(100vw - 112px)); }
        99% { transform: translateX(-88px); }
        100% { transform: translateX(-88px); }
      }
      @keyframes commitgotchi-face {
        0%, 49% { transform: scaleX(1); }
        50%, 99% { transform: scaleX(-1); }
        100% { transform: scaleX(1); }
      }
      @media (prefers-reduced-motion: reduce) {
        .walker { animation: none; transform: none; }
        .sprite { animation: none; transform: none; }
      }
    `

    const walker = document.createElement('div')
    walker.className = 'walker'
    const sprite = document.createElement('div')
    sprite.className = 'sprite'
    sprite.innerHTML = spriteMarkup(gotchi)
    const ground = document.createElement('div')
    ground.className = 'ground'
    const name = document.createElement('div')
    name.className = 'name'
    name.textContent = gotchi.name
    walker.append(sprite, ground, name)
    shadow.append(style, walker)
  }

  const initialRevision = changeRevision
  chrome.storage.local.get(STORAGE_KEY)
    .then(result => {
      if (changeRevision === initialRevision) renderGotchi(result[STORAGE_KEY])
    })
    .catch(() => {
      if (changeRevision === initialRevision) removeGotchi()
    })

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === 'local' && Object.prototype.hasOwnProperty.call(changes, STORAGE_KEY)) {
      changeRevision++
      renderGotchi(changes[STORAGE_KEY].newValue)
    }
  })
})()
