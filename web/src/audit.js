// Explicit UI events supplement server-side API auditing. Never send passwords, SQL or tokens in the body.
export function recordClientEvent(action, target) {
  send('/api/audit/events', {action, target:String(target).slice(0,100)})
}
export function recordLogout() { send('/api/auth/logout', {}) }
function send(path, body) {
  const token = sessionStorage.getItem('dataask_token')
  if (!token) return
  // Capture credentials now: a later account change must not attribute an old event to the new account.
  fetch(path, {method:'POST', headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},
    body:JSON.stringify(body), keepalive:true}).catch(() => {})
}
