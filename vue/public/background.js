/*
 * MV3 service worker.
 * 아이콘 클릭 시 SPA를 별도 팝업 창(type:'popup')으로 연다.
 * 이미 열려 있으면 그 창을 포커스(중복 생성 방지).
 *
 * 작은 드롭다운 팝업으로 바꾸고 싶다면: 이 파일과 background 항목을 지우고,
 * manifest 의 action 에 "default_popup": "index.html" 을 추가하면 된다
 * (단, 드롭다운은 최대 ~800x600 이라 데스크톱 레이아웃은 좁아진다).
 */
const POPUP = { width: 1240, height: 860 }
let popupWindowId = null

chrome.action.onClicked.addListener(async () => {
  // 이미 열린 창이 있으면 포커스
  if (popupWindowId !== null) {
    try {
      await chrome.windows.update(popupWindowId, { focused: true })
      return
    } catch {
      popupWindowId = null // 창이 닫혀 있었음 → 새로 생성
    }
  }
  const win = await chrome.windows.create({
    url: chrome.runtime.getURL('index.html'),
    type: 'popup',
    width: POPUP.width,
    height: POPUP.height,
  })
  popupWindowId = win.id
})

chrome.windows.onRemoved.addListener((closedId) => {
  if (closedId === popupWindowId) popupWindowId = null
})
