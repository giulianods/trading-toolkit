import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnInit,
  QueryList,
  ViewChildren,
} from '@angular/core';
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

interface BreadthIndicator {
  date: string;
  day_4pct_up: number;
  day_4pct_down: number;
  qt_25pct_up: number;
  qt_25pct_down: number;
  monthly_25pct_up: number;
  monthly_25pct_down: number;
  monthly_50pct_up: number;
  monthly_50pct_down: number;
  bull_34_13: number;
  bear_34_13: number;
  ten_day_ratio?: number | null;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, AfterViewInit {
  selectedTab: 'charts' | 'breadth' = 'charts';
  symbols: string[] = [];
  descriptions: { [symbol: string]: StockDescription } = {};
  stats: { [ticker: string]: { [key: string]: string } } = {};
  breadthIndicators: BreadthIndicator[] = [];
  breadthIndicatorColumns: { key: keyof BreadthIndicator; label: string }[] = [
    { key: 'date', label: 'Date' },
    { key: 'day_4pct_up', label: 'Day +4%' },
    { key: 'day_4pct_down', label: 'Day -4%' },
    { key: 'ten_day_ratio', label: '10 Day Ratio' },
    { key: 'qt_25pct_up', label: 'Quarter +25%' },
    { key: 'qt_25pct_down', label: 'Quarter -25%' },
    { key: 'monthly_25pct_up', label: 'Month +25%' },
    { key: 'monthly_25pct_down', label: 'Month -25%' },
    { key: 'monthly_50pct_up', label: 'Month +50%' },
    { key: 'monthly_50pct_down', label: 'Month -50%' },
    { key: 'bull_34_13', label: 'Bull 34/13' },
    { key: 'bear_34_13', label: 'Bear 34/13' }
  ];

  watchlists: { label: string; value: string }[] = [
    { label: 'SEPA', value: 'SEPA' },
    { label: '7% Up', value: '7_PCT_UP' },
    { label: '7% Down', value: '7_PCT_DOWN' },
    { label: 'VCP', value: 'VCP' }
  ];

  selectedWatchlist: string = this.watchlists[0].value;
  todayString: string;
  selectedDay: string;
  focusedIndex = 0;

  @ViewChildren('stockCard')
  stockCards!: QueryList<ElementRef<HTMLDivElement>>;
  
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

  constructor(private http: HttpClient) {
    this.todayString = this.formatDate(new Date());
    this.selectedDay = this.todayString;
  }

  ngOnInit(): void {
    this.loadWatchlist(this.selectedWatchlist);
  }

  ngAfterViewInit(): void {
    this.stockCards.changes.subscribe(() => {
      this.focusStock(this.focusedIndex);
    });
  }

  selectTab(tab: 'charts' | 'breadth'): void {
    this.selectedTab = tab;

    if (tab === 'breadth' && !this.breadthIndicators.length) {
      this.loadBreadthIndicators();
    }
  }

  loadWatchlist(watchlist: string): void {
    this.selectedWatchlist = watchlist;
    this.symbols = [];
    this.descriptions = {};
    this.stats = {};
    this.focusedIndex = 0;

    const url = this.buildWatchlistUrl(watchlist);

    this.http.get(url, { responseType: 'json' }).subscribe((data: any) => {
      this.symbols = data['tickers'].slice(0, 100);

      setTimeout(() => {
        this.focusStock(this.focusedIndex);
      });

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

  loadBreadthIndicators(): void {
    this.http
      .get<BreadthIndicator[]>('/breadth/indicators')
      .subscribe((data: BreadthIndicator[]) => {
        const sortedIndicators = [...data].sort((a, b) =>
          a.date < b.date ? 1 : a.date > b.date ? -1 : 0
        );

        this.breadthIndicators = sortedIndicators.map((indicator, index, array) => {
          const window = array.slice(index, index + 10);
          const totalUp = window.reduce((sum, item) => sum + (item.day_4pct_up ?? 0), 0);
          const totalDown = window.reduce((sum, item) => sum + (item.day_4pct_down ?? 0), 0);
          const tenDayRatio = totalDown === 0 ? null : Number((totalUp / totalDown).toFixed(2));

          return {
            ...indicator,
            ten_day_ratio: tenDayRatio
          };
        });
      });
  }

  formatBreadthIndicatorValue(
    indicator: BreadthIndicator,
    key: keyof BreadthIndicator
  ): string {
    const value = indicator[key];

    if (value === null || value === undefined) {
      return '—';
    }

    if (typeof value === 'number') {
      return value.toString();
    }

    return value;
  }

  onDateChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedDay = input.value || this.todayString;
    this.loadWatchlist(this.selectedWatchlist);
  }

  chartUrl(symbol: string): string {
    let url = `/chart/${symbol}?days=200`;

    if (this.selectedDay && this.selectedDay !== this.todayString) {
      url += `&day=${this.selectedDay}`;
    }

    return url;
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

  @HostListener('window:keydown', ['$event'])
  handleKeydown(event: KeyboardEvent): void {
    if (!this.symbols.length) {
      return;
    }

    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.syncFocusedIndexWithViewport();

      if (event.key === 'ArrowDown') {
        this.focusNext();
      } else {
        this.focusPrevious();
      }
    }
  }

  private focusNext(): void {
    const nextIndex = Math.min(this.focusedIndex + 1, this.symbols.length - 1);
    this.focusStock(nextIndex);
  }

  private focusPrevious(): void {
    const previousIndex = Math.max(this.focusedIndex - 1, 0);
    this.focusStock(previousIndex);
  }

  private focusStock(index: number): void {
    if (!this.symbols.length) {
      return;
    }

    const boundedIndex = Math.min(Math.max(index, 0), this.symbols.length - 1);
    this.focusedIndex = boundedIndex;

    const card = this.stockCards?.toArray()[boundedIndex];
    if (!card) {
      return;
    }

    const element = card.nativeElement;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  private syncFocusedIndexWithViewport(): void {
    if (!this.stockCards || !this.stockCards.length) {
      return;
    }

    const cards = this.stockCards.toArray();
    const viewportCenter = window.innerHeight / 2;

    let closestIndex = this.focusedIndex;
    let closestDistance = Number.POSITIVE_INFINITY;

    cards.forEach((card, index) => {
      const rect = card.nativeElement.getBoundingClientRect();
      const cardCenter = rect.top + rect.height / 2;
      const distance = Math.abs(cardCenter - viewportCenter);

      if (distance < closestDistance) {
        closestDistance = distance;
        closestIndex = index;
      }
    });

    this.focusedIndex = closestIndex;
  }

  private buildWatchlistUrl(watchlist: string): string {
    if (this.selectedDay && this.selectedDay !== this.todayString) {
      return `/watchlists/${watchlist}?day=${this.selectedDay}`;
    }

    return `/watchlists/${watchlist}`;
  }

  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  onCardFocus(index: number): void {
    this.focusedIndex = index;
  }
}
