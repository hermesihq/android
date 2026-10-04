// A development token server for the sample apps. NOT for production.
//
// A subscriber token proves to Hermesi which subscriber a device belongs to, and it is signed with your SECRET key,
// which must never be inside an app. In a real product your own backend mints it for the person who is signed in. This
// stands in for that backend so the sample can run: it mints a token for whatever subscriber the request names, with
// no authentication at all. Anyone who can reach it can act as any of your subscribers, so it listens on the loopback
// interface only unless you say otherwise.
//
//   HERMESI_SECRET_KEY=hm_sk_... HERMESI_ENVIRONMENT_ID=env_... node server.mjs
//   curl 'http://127.0.0.1:8787/token?subscriber=user_1'   ->   {"token":"..."}
//
// Needs Node 18 or later and nothing else.
import { createServer } from 'node:http'
import { mintSubscriberToken } from './mint.mjs'

const secretKey = process.env.HERMESI_SECRET_KEY
const environmentId = process.env.HERMESI_ENVIRONMENT_ID
const port = Number(process.env.PORT ?? 8787)
// 127.0.0.1 reaches the Android emulator (as 10.0.2.2) and the iOS simulator. A physical phone needs your machine's
// address on the network, so set HOST=0.0.0.0, and only on a network you trust.
const host = process.env.HOST ?? '127.0.0.1'
const ttlSeconds = 3600

if (!secretKey || !environmentId) {
  console.error('Set HERMESI_SECRET_KEY (hm_sk_...) and HERMESI_ENVIRONMENT_ID (env_...).')
  process.exit(1)
}

createServer((request, response) => {
  const url = new URL(request.url ?? '/', 'http://localhost')
  const subscriber = url.searchParams.get('subscriber')
  if (request.method !== 'GET' || url.pathname !== '/token' || !subscriber) {
    response.writeHead(400, { 'content-type': 'application/json' })
    return response.end(JSON.stringify({ error: 'GET /token?subscriber=<external_id>' }))
  }
  response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  response.end(JSON.stringify({ token: mintSubscriberToken(secretKey, subscriber, environmentId) }))
}).listen(port, host, () => {
  console.log(`token server on http://${host}:${port}  (development only: it mints a token for anyone who asks)`)
})
