// Page-local asynchronous ownership; server authorization still uses its session.
export function createPageTaskScope(identity) {
  let generation = 0, visible = false, disposed = false
  const validIdentity = who => Number.isSafeInteger(who?.userId) && who.userId > 0 &&
    typeof who.token === 'string' && who.token.length > 0
  return {
    show() { if (disposed) return false; visible = true; generation++; return true },
    hide() { visible = false; generation++ },
    invalidate() { generation++ },
    dispose() { disposed = true; visible = false; generation++ },
    capture() {
      const who = identity()
      if (disposed || !visible || !validIdentity(who)) return null
      return Object.freeze({ generation, userId: who.userId, token: who.token })
    },
    valid(ticket) {
      if (!ticket || disposed || !visible || ticket.generation !== generation) return false
      const who = identity()
      return validIdentity(who) && who.userId === ticket.userId && who.token === ticket.token
    }
  }
}
