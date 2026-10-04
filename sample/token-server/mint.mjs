import { createHash, createHmac } from 'node:crypto'

/**
 * A subscriber token, in the format Hermesi's integration guide gives: `base64url(payload).base64url(hmac)`, where the
 * HMAC key is the SHA-256 hex digest of the raw secret key, not the key itself. Signing with the raw key is the
 * mistake that makes Hermesi reject every token.
 */
export function mintSubscriberToken(secret, externalId, environment, ttlSeconds = 3600, now = Date.now()) {
  const keyHash = createHash('sha256').update(secret).digest('hex')
  const payload = { sub: externalId, env: environment, exp: Math.floor(now / 1000) + ttlSeconds }
  const payloadB64 = Buffer.from(JSON.stringify(payload)).toString('base64url')
  const signature = createHmac('sha256', keyHash).update(payloadB64).digest('base64url')
  return `${payloadB64}.${signature}`
}
