const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function (app) {
  const proxy = createProxyMiddleware({
    target: 'http://localhost:7777',
    changeOrigin: true,
  });
  app.use('/markets', proxy);
  app.use('/watchlists', proxy);
  app.use('/quotes', proxy);
  app.use('/financials', proxy);
  app.use('/description', proxy);
  app.use('/descriptions', proxy);
  app.use('/breadth', proxy);
  app.use('/status', proxy);
};
