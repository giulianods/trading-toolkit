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
  breadthIndicatorColumns: {
    key: keyof BreadthIndicator;
    label: string;
    tooltip?: string;
  }[] = [
    { key: 'date', label: 'Date' },
    {
      key: 'day_4pct_up',
      label: 'Day +4%',
      tooltip:
        'up to 300 normal buying pressure\n300 to 500 high buying pressure money flowing in to market\n500 to1000 very high buying pressure (normally seen at beginning of a bullish turn from bearish phase)\n1000 plus extreme buying pressure\n300 plus day are common in bear markets.\nAt beginning of a bull move you will see a cluster of 3 to 5 big buying days of 300 plus.\nMost turns in major trend start with 1000 plus buying day.'
    },
    {
      key: 'day_4pct_down',
      label: 'Day -4%',
      tooltip:
        'up to 300 normal selling pressure\n500 to 1000 very high selling pressure\n1000 plus extreme selling pressure\nBear  markets rallies typically start after such extreme 1000 plus selling days.\nSo a 1000 plus days after a 5 to 10 days of selling pressure is short term bullish.'
    },
    {
      key: 'ten_day_ratio',
      label: '10 Day Ratio',
      tooltip:
        'When market is in bearish phase first time ratio is  2 plus signals start of a bull move.\nBull markets start with a bullish thrust.\nBear markets often end with a bearish thrust.\nRatio below .50 signals start of a bearish move after a bull move has been in progress.\n2 plus readings are good for swing trading on long side.\n.5 or less readings are good for swing trading on short side.'
    },
    {
      key: 'qt_25pct_up',
      label: 'Quarter +25%',
      tooltip:
        'This is a primary indicator\nMarket is in bullish phase if 25% plus quarter>25% down quarter.\nMarket is in bearish phase if 25% plus quarter<25% down quarter.\nNumbers below 200 are considered extreme.\nWhen 25% plus quarter number  goes below 200 it is extremely bullish. It indicates extreme bearishness.\nRallies which start from readings below 200 are extremely powerful. (I make my IRA/Roth IRA buys on such days)\nEnd of day readings of below 200 on 25% plus quarter are rare, most of the time 200 readings are reached intra day for few hours or minutes and market rebounds.\nSo when  readings drop to below 500 be on watch out for a reversal of the bearish trend.'
    },
    {
      key: 'qt_25pct_down',
      label: 'Quarter -25%',
      tooltip:
        'This is a primary indicator\n\nMarket is in bullish phase till 25% plus quarter>25% down quarter.\nMarket is in bearish phase till 25% plus quarter<25% down quarter.\nWhen this number goes below 200 it is bearish. It indicates extreme bullishness.\nReadings below 200 are considered extreme readings.\nUnlike end of bear market in bull market, markets do not turn immediately after such high readings.\nThere is a delay of 2 to 6 weeks before real selling might start and a top is formed after readings reach extreme levels '
    },
    { key: 'monthly_25pct_up', label: 'Month +25%' },
    { key: 'monthly_25pct_down', label: 'Month -25%' },
    {
      key: 'monthly_50pct_up',
      label: 'Month +50%',
      tooltip:
        'This is a secondary indicator.\nThis indicator tells you intermediate term extreme bullish phases and likely pullback/correction points\nReadings on 50% plus month above 20 are bearish.\nThey indicate high bullishness and tend to lead to correction.\nMarket resumes its bullish move once such high readings drop below 10.\nReadings of below 3 are bullish on 50% plus month.\nThey indicate extreme bearishness.\n50% down month indicator tells you intermediate term extreme bearish phases and likely counter trend rally  points during bear market.\n50% down month readings above 20 indicate high bearishness and tend to lead to reflex rallies.'
    },
    {
      key: 'monthly_50pct_down',
      label: 'Month -50%',
      tooltip:
        'This is a secondary indicator.\nThis indicator tells you intermediate term extreme bullish phases and likely pullback/correction points\nReadings on 50% plus month above 20 are bearish.\nThey indicate high bullishness and tend to lead to correction.\nMarket resumes its bullish move once such high readings drop below 10.\nReadings of below 3 are bullish on 50% plus month.\nThey indicate extreme bearishness.\n50% down month indicator tells you intermediate term extreme bearish phases and likely counter trend rally  points during bear market.\n50% down month readings above 20 indicate high bearishness and tend to lead to reflex rallies.'
    },
    {
      key: 'bull_34_13',
      label: 'Bull 34/13',
      tooltip:
        '34/13 is a faster version of 25% plus or down in a quarter.\n34/13 bull looks for half of that move in half the time frame.\nSo it gives buy and sell signal faster compared to Primary indicator.\nBut it tends to be noisy.'
    },
    {
      key: 'bear_34_13',
      label: 'Bear 34/13',
      tooltip:
        '34/13 is a faster version of 25% plus or down in a quarter.\n34/13 bull looks for half of that move in half the time frame.\nSo it gives buy and sell signal faster compared to Primary indicator.\nBut it tends to be noisy.'
    }
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
  hasUserNavigated = false;

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
      this.focusStock(this.focusedIndex, {
        allowFocus: this.hasUserNavigated,
        allowScroll: this.hasUserNavigated
      });
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
    this.hasUserNavigated = false;

    const url = this.buildWatchlistUrl(watchlist);

    this.http.get(url, { responseType: 'json' }).subscribe((data: any) => {
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

  getBreadthCellClasses(
    indicator: BreadthIndicator,
    key: keyof BreadthIndicator
  ): { [klass: string]: boolean } {
    const value = indicator[key];
    const classes: { [klass: string]: boolean } = {};

    if (typeof value !== 'number') {
      return classes;
    }

    switch (key) {
      case 'ten_day_ratio':
        classes['breadth-positive'] = value > 2;
        classes['breadth-negative'] = value < 0.5;
        break;
      case 'day_4pct_up':
        classes['breadth-positive'] = value > 250;
        break;
      case 'day_4pct_down':
        classes['breadth-negative'] = value > 250;
        break;
      case 'qt_25pct_up':
      case 'qt_25pct_down': {
        const up = indicator.qt_25pct_up;
        const down = indicator.qt_25pct_down;

        if (typeof up === 'number' && typeof down === 'number') {
          const isBullish = up > down;
          classes['breadth-positive'] = isBullish;
          classes['breadth-negative'] = !isBullish;
        }

        break;
      }
      case 'monthly_25pct_up':
      case 'monthly_25pct_down': {
        const up = indicator.monthly_25pct_up;
        const down = indicator.monthly_25pct_down;

        if (typeof up === 'number' && typeof down === 'number') {
          const isBullish = up > down;
          classes['breadth-positive'] = isBullish;
          classes['breadth-negative'] = !isBullish;
        }

        break;
      }
      default:
        break;
    }

    return classes;
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
      this.hasUserNavigated = true;
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

  private focusStock(
    index: number,
    options: { allowFocus?: boolean; allowScroll?: boolean } = {}
  ): void {
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
    const shouldFocus = options.allowFocus ?? true;
    const shouldScroll = options.allowScroll ?? true;

    if (shouldFocus) {
      element.focus({ preventScroll: !shouldScroll });
    }

    if (shouldScroll) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
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
    this.hasUserNavigated = true;
    this.focusedIndex = index;
  }
}
