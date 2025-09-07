document.addEventListener('DOMContentLoaded', function () {
  var root = document.querySelector('app-root');
  if (!root) return;
  fetch('/watchlists/SEPA', { headers: { 'Accept': 'text/plain' } })
    .then(function (r) { return r.text(); })
    .then(function (txt) {
      var symbols = txt.split('\n').map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
      var header = document.createElement('header');
      var h1 = document.createElement('h1');
      h1.textContent = 'greensheep-stocks';
      header.appendChild(h1);
      root.innerHTML = '';
      root.appendChild(header);
      var charts = document.createElement('div');
      charts.className = 'charts';
      root.appendChild(charts);
      symbols.forEach(function (s) {
        var div = document.createElement('div');
        div.className = 'chart';
        var img = document.createElement('img');
        img.src = '/chart/' + s + '?days=200';
        img.alt = s;
        div.appendChild(img);
        charts.appendChild(div);
      });
    });
});
