/*
 * 확장 팝업 크기 훅.
 * 크롬 MV3 확장 페이지의 기본 CSP는 `script-src 'self'` 라서 index.html 안의
 * 인라인 <script> 는 차단된다. 그래서 이 코드는 반드시 외부 파일(self)로 둔다.
 *
 * 첫 페인트 전에 <head> 에서 동기 실행되어 .is-ext-popup 클래스를 붙인다.
 * 크롬은 팝업이 열리는 즉시 콘텐츠 크기를 재므로, 이 클래스가 일찍 붙어야
 * index.html <head> 인라인 <style> 의 780x600 고정 크기가 측정에 반영된다.
 * (인라인 style 은 CSP script-src 대상이 아니므로 허용됨.)
 */
if (location.protocol === 'chrome-extension:') {
  document.documentElement.classList.add('is-ext-popup')
}
