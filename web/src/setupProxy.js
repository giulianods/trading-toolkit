const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function (app) {
  app.use(
    '/watchlists',
    createProxyMiddleware({
      target: 'http://localhost:7777',
      changeOrigin: true,
    })
  );
  app.use(
    '/quotes',
    createProxyMiddleware({
      target: 'http://localhost:7777',
      changeOrigin: true,
    })
  );
};
