import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

interface StockDescription {
  symbol: string;
  companyName: string;
  ipoDate: string;
  primaryExchange: string;
  description: string;
  totalEmployees: number;
  sicCode: string;
  sicDescription: string;
  marketCap: number;
  weightedSharesOutstanding: number;
}

interface StockStats {
  ticker: string;
  stats: { [key: string]: string };
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  symbols: string[] = [];
  descriptions: { [symbol: string]: StockDescription } = {};
  stats: { [ticker: string]: { [key: string]: string } } = {};

  watchlists: { label: string; value: string }[] = [
    { label: 'SEPA', value: 'SEPA' },
    { label: '7% Up', value: '7_PCT_UP' },
    { label: '7% Down', value: '7_PCT_DOWN' },
    { label: 'VCP', value: 'VCP' }
  ];

  selectedWatchlist: string = this.watchlists[1].value;
  
  // Mapping of stat keys to display names and their order
  private statDisplayMapping: { [key: string]: string } = {
    'IBD rank': 'IBD rank',
    'IBD strength': 'IBD strength',
    'currentPrice': 'Price',
    'ma50': '50 Day MA',
    'ma150': '150 Day MA',
    'ma200': '200 Day MA',
    'perf1q': '1Q Performance',
    'perf2q': '2Q Performance',
    'perf3q': '3Q Performance',
    'perf4q': '4Q Performance',
  };

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.loadWatchlist(this.selectedWatchlist);
  }

  loadWatchlist(watchlist: string): void {
    this.selectedWatchlist = watchlist;
    this.symbols = [];
    this.descriptions = {};
    this.stats = {};

    this.http.get(`/watchlists/${watchlist}`, { responseType: 'json' }).subscribe((data: any) => {
      this.symbols = data['tickers'].slice(0, 100);

      // Extract and store stats data
      if (data['stats']) {
        data['stats'].forEach((statItem: StockStats) => {
          this.stats[statItem.ticker] = statItem.stats;
        });
      }

      this.symbols.forEach(s => {
        this.http.get<StockDescription>(`/description/${s}`).subscribe(desc => {
          this.descriptions[s] = desc;
        });
      });
    });
  }

  onWatchlistChange(event: Event): void {
    const selectedWatchlist = (event.target as HTMLSelectElement).value;
    this.loadWatchlist(selectedWatchlist);
  }

  chartUrl(symbol: string): string {
    return `/chart/${symbol}?days=200`;
  }

  getStatsArray(ticker: string): { key: string; value: string }[] {
    if (!this.stats[ticker]) {
      return [];
    }
    
    // Get stats in the order defined by the mapping
    const orderedStats: { key: string; value: string }[] = [];
    
    // First, add stats that are in our mapping in the defined order
    Object.keys(this.statDisplayMapping).forEach(statKey => {
      if (this.stats[ticker][statKey]) {
        orderedStats.push({
          key: this.statDisplayMapping[statKey],
          value: this.stats[ticker][statKey]
        });
      }
    });
    
    // Then, add any remaining stats that aren't in our mapping
    Object.entries(this.stats[ticker]).forEach(([key, value]) => {
      if (!this.statDisplayMapping[key]) {
        orderedStats.push({ key, value });
      }
    });
    
    return orderedStats;
  }
}
