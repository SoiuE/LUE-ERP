import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const certPath = fileURLToPath(new URL('../public/mobile-dev-root-ca.crt', import.meta.url))
const cert = readFileSync(certPath)
createServer((request, response) => {
  if (request.url !== '/mobile-dev-root-ca.crt') {
    response.writeHead(404).end('Not found')
    return
  }
  response.writeHead(200, {
    'Content-Type': 'application/x-x509-ca-cert',
    'Content-Disposition': 'attachment; filename="mobile-dev-root-ca.crt"',
    'Content-Length': cert.length,
  }).end(cert)
}).listen(5174, '0.0.0.0', () => {
  console.log('CA certificate download server: http://0.0.0.0:5174/mobile-dev-root-ca.crt')
})
