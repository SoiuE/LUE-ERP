param(
  [Parameter(Mandatory=$true)]
  [string]$IpAddress
)

$ErrorActionPreference = 'Stop'
$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if (-not $openssl) {
  throw 'OpenSSL was not found. Install OpenSSL and retry.'
}
$opensslLibrary = Split-Path (Split-Path $openssl.Source -Parent) -Parent
$opensslConfig = Join-Path $opensslLibrary 'ssl\openssl.cnf'
if (Test-Path $opensslConfig) { $env:OPENSSL_CONF = $opensslConfig }
if ($IpAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
  throw 'IpAddress must be the computer LAN IPv4 address.'
}

$certDir = Join-Path $PSScriptRoot '..\.mobile-https'
$publicDir = Join-Path $PSScriptRoot '..\public'
New-Item -ItemType Directory -Force -Path $certDir, $publicDir | Out-Null
$rootKey = Join-Path $certDir 'root-ca.key'
$rootCert = Join-Path $certDir 'root-ca.crt'
$serverKey = Join-Path $certDir 'server.key'
$serverCsr = Join-Path $certDir 'server.csr'
$serverCert = Join-Path $certDir 'server.crt'
$extensionFile = Join-Path $certDir 'server.ext'

& $openssl.Source req -x509 -newkey rsa:2048 -nodes -keyout $rootKey -out $rootCert -days 3650 -subj '/CN=LUE ERP Mobile Development Root CA' -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate the local root certificate.' }
& $openssl.Source req -new -newkey rsa:2048 -nodes -keyout $serverKey -out $serverCsr -subj "/CN=$IpAddress" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate the local HTTPS certificate request.' }
@"
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=IP:$IpAddress
"@ | Set-Content -Encoding ascii $extensionFile
& $openssl.Source x509 -req -in $serverCsr -CA $rootCert -CAkey $rootKey -CAcreateserial -out $serverCert -days 825 -sha256 -extfile $extensionFile | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to sign the local HTTPS certificate.' }
Copy-Item -Force $rootCert (Join-Path $publicDir 'mobile-dev-root-ca.crt')
Write-Output "Download the public root certificate at http://$IpAddress`:5174/mobile-dev-root-ca.crt."
Write-Output "After installing and trusting it on the iPhone, open https://$IpAddress`:5173/mobile."
Write-Output 'On iPhone, install the downloaded profile under Settings > General > VPN & Device Management, then enable trust under Settings > General > About > Certificate Trust Settings.'
Write-Output 'Private keys are stored in frontend/.mobile-https/. Do not share them.'
