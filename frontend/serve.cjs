const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3002;
const API_HOST = 'localhost';
const API_PORT = 8080;
const DIST_DIR = path.join(__dirname, 'dist');

// Proxy API requests to backend
function proxyRequest(req, res) {
  const options = {
    hostname: API_HOST,
    port: API_PORT,
    path: req.url,
    method: req.method,
    headers: { ...req.headers, host: `${API_HOST}:${API_PORT}` }
  };

  const proxyReq = http.request(options, (proxyRes) => {
    res.writeHead(proxyRes.statusCode, proxyRes.headers);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error('Proxy error:', err.message);
    res.writeHead(502);
    res.end('Bad Gateway');
  });

  req.pipe(proxyReq);
}

const server = http.createServer((req, res) => {
  // API requests -> backend
  if (req.url.startsWith('/api')) {
    return proxyRequest(req, res);
  }

  // 静态文件：先剥掉 query string，再做路径穿越防护
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch (e) {
    // 非法百分号编码等，直接拒绝
    res.writeHead(400);
    res.end('Bad Request');
    return;
  }

  // 归一化后拼接，再校验结果仍位于 dist/ 内（防 ../ 穿越读取工程源码）
  const distRoot = path.normalize(DIST_DIR);
  let filePath = path.normalize(path.join(distRoot, pathname === '/' ? 'index.html' : pathname));
  const relative = path.relative(distRoot, filePath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  // 目录请求回退 index.html；不存在路径走 SPA fallback
  try {
    if (fs.statSync(filePath).isDirectory()) {
      filePath = path.join(distRoot, 'index.html');
    }
  } catch (e) {
    filePath = path.join(distRoot, 'index.html');
  }

  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end('Not Found');
    return;
  }

  const ext = path.extname(filePath);
  const contentTypes = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.ico': 'image/x-icon',
    '.svg': 'image/svg+xml'
  };

  res.setHeader('Content-Type', contentTypes[ext] || 'application/octet-stream');
  fs.createReadStream(filePath).pipe(res);
});

server.listen(PORT, () => {
  console.log(`Server: http://localhost:${PORT}`);
  console.log(`API proxy: http://localhost:${API_PORT}`);
});
