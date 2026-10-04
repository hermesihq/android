import assert from 'node:assert/strict'
import { createHash, createHmac } from 'node:crypto'
import { test } from 'node:test'
import { mintSubscriberToken } from './mint.mjs'

const decode = (part) => JSON.parse(Buffer.from(part, 'base64url').toString())

test('the payload names the subscriber, the environment and an expiry an hour out', () => {
  const token = mintSubscriberToken('hm_sk_test', 'user_1', 'env_1', 3600, 1_700_000_000_000)

  const [payload] = token.split('.')
  assert.deepEqual(decode(payload), { sub: 'user_1', env: 'env_1', exp: 1_700_003_600 })
})

test('the signature is an HMAC keyed with the SHA-256 hex digest of the secret key, not the key', () => {
  const token = mintSubscriberToken('hm_sk_test', 'user_1', 'env_1')

  const [payload, signature] = token.split('.')
  const keyHash = createHash('sha256').update('hm_sk_test').digest('hex')
  assert.equal(signature, createHmac('sha256', keyHash).update(payload).digest('base64url'))
  assert.notEqual(signature, createHmac('sha256', 'hm_sk_test').update(payload).digest('base64url'), 'the raw key must not sign')
})

test('two secrets give different signatures, and the token has no padding or unsafe characters', () => {
  const a = mintSubscriberToken('hm_sk_a', 'u', 'e', 3600, 0)
  const b = mintSubscriberToken('hm_sk_b', 'u', 'e', 3600, 0)

  assert.notEqual(a.split('.')[1], b.split('.')[1])
  assert.match(a, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/)
})
