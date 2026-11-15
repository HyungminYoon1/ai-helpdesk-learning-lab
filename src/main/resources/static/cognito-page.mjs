const status = document.querySelector('#login-status')
const forms = Object.fromEntries(Array.from(document.forms, form => [form.id, form]))
const screen = {
  START: ['초대 코드를 입력하거나 Cognito 계정으로 로그인하세요.', 'start-login'],
  FIRST_LOGIN: ['로그인을 마치려면 다시 시작하세요.', 'start-login'],
  VERIFY_EMAIL: ['Cognito가 보낸 메일의 코드를 입력하세요.', 'send-code', 'verify-email'],
  EMAIL_CONFIRMED: ['이메일을 확인했습니다. 인증 앱으로 새 로그인에 응답하세요.', 'reauthenticate'],
  LOGOUT: ['인증 앱으로 로그인하려면 다시 시작하세요.', 'start-login'],
  SECOND_LOGIN: ['인증 앱으로 로그인하려면 다시 시작하세요.', 'start-login'],
  ACCEPT: ['재로그인을 확인했습니다. Helpdesk 계정을 활성화하세요.', 'accept-invite'],
}
const callbackErrors = {
  FRESH_AUTHENTICATION_REQUIRED: '인증 앱을 사용한 새 로그인을 확인해야 합니다.',
  INVITATION_UNAVAILABLE: '이 계정으로 수락할 수 있는 초대를 확인해주세요.',
  SIGN_IN_REQUIRED: 'Cognito 로그인을 완료해주세요.',
  COGNITO_UNAVAILABLE: '로그인 제공자를 확인할 수 없습니다. 다시 시도해주세요.',
}

async function refreshStatus() {
  const response = await window.fetch('/onboarding/state', { cache: 'no-store', credentials: 'same-origin' })
  if (!response.ok) throw new Error('onboarding unavailable')
  const state = await response.json()
  const [hint, ...visible] = screen[state.step] ?? screen.START
  for (const [id, form] of Object.entries(forms)) {
    const previous = form.querySelector('input[type="hidden"]')
    if (previous) previous.remove()
    const csrf = document.createElement('input')
    csrf.type = 'hidden'
    csrf.name = state.csrfName
    csrf.value = state.csrfToken
    form.append(csrf)
    form.hidden = !visible.includes(id)
  }
  const error = new URLSearchParams(window.location.search).get('error')
  status.textContent = Object.hasOwn(callbackErrors, error) ? callbackErrors[error] : hint
  if (error) window.history.replaceState(null, '', window.location.pathname)
}

for (const form of [forms['send-code'], forms['verify-email']]) {
  form.addEventListener('submit', async event => {
    event.preventDefault()
    const button = form.querySelector('button')
    if (button.disabled) return
    button.disabled = true
    try {
      const response = await window.fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new window.FormData(form)),
        credentials: 'same-origin',
      })
      if (!response.ok) {
        status.textContent = '코드를 처리하지 못했습니다. 다시 확인해주세요.'
        return
      }
      form.reset()
      await refreshStatus()
      if (form.id === 'send-code') status.textContent = '이메일 확인 코드를 요청했습니다.'
    } catch {
      status.textContent = '메일 확인 요청을 처리할 수 없습니다.'
    } finally {
      button.disabled = false
    }
  })
}

try {
  await refreshStatus()
} catch {
  status.textContent = '로그인 화면을 준비할 수 없습니다. 잠시 뒤 다시 시도해주세요.'
}
