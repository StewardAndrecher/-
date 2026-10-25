// Only explicit login-protection responses initiate a local waiting hint.
export function loginRetrySeconds(error) {
  const limited = error?.statusCode === 429 && error?.code === 'LOGIN_RATE_LIMITED'
  const unavailable = error?.statusCode === 503 && error?.code === 'LOGIN_PROTECTION_UNAVAILABLE'
  if (!limited && !unavailable) return 0
  const seconds = error.retryAfterSeconds
  return Number.isInteger(seconds) && seconds > 0 && seconds <= 86400
    ? seconds : limited ? 30 : 5
}

export function createLoginCooldown({ clock = () => Date.now() } = {}) {
  let deadline = 0
  return {
    remaining() { return Math.max(0, Math.ceil((deadline - clock()) / 1000)) },
    start(error) {
      const seconds = loginRetrySeconds(error)
      if (!seconds) return false
      deadline = Math.max(deadline, clock() + seconds * 1000)
      return true
    },
    clear() { deadline = 0 }
  }
}
