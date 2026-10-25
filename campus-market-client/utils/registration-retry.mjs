export function registrationRetrySeconds(error) {
 const limited=error?.statusCode===429&&error?.code==='REGISTRATION_RATE_LIMITED'
 const unavailable=error?.statusCode===503&&error?.code==='REGISTRATION_PROTECTION_UNAVAILABLE'
 if(!limited&&!unavailable)return 0
 const seconds=error.retryAfterSeconds
 return Number.isInteger(seconds)&&seconds>0&&seconds<=86400?seconds:limited?30:5
}
export function createRegistrationCooldown({clock=()=>Date.now()}={}) {
 let deadline=0
 return {
  remaining(){return Math.max(0,Math.ceil((deadline-clock())/1000))},
  start(error){const seconds=registrationRetrySeconds(error);if(!seconds)return false;deadline=Math.max(deadline,clock()+seconds*1000);return true},
  clear(){deadline=0}
 }
}
