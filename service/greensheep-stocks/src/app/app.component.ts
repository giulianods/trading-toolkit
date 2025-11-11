import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  QueryList,
  ViewChildren
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import Chart from 'chart.js/auto';

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

interface WatchlistHistorySummary {
  name: string;
  symbolCount: number;
}

interface WatchlistHistoryEntry {
  day: string;
  watchlists: WatchlistHistorySummary[];
}

interface WatchlistChartGroup {
  id: string;
  title: string;
  datasets: {
    watchlist: string;
    label: string;
    backgroundColor: string;
    borderColor: string;
  }[];
}

interface AnnualFinancialData {
  year: number;
  revenuePerShare: number;
  basicEarningsPerShare: number;
  operatingMargin: number;
  netIncomeMargin: number;
}

interface StockFinancials {
  ticker: string;
  annual: AnnualFinancialData[];
  quarterly: unknown[];
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, AfterViewInit, OnDestroy {
  selectedTab: 'charts' | 'breadth' | 'history' = 'charts';
  symbols: string[] = [];
  descriptions: { [symbol: string]: StockDescription } = {};
  stats: { [ticker: string]: { [key: string]: string } } = {};
  financials: { [ticker: string]: AnnualFinancialData[] } = {};
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
    { label: 'VCP', value: 'VCP' },
    { label: '52 Week High', value: 'NEW_52_WEEK_HIGH' },
    { label: '52 Week Low', value: 'NEW_52_WEEK_LOW' },
    { label: 'New IPOs', value: 'NEW_IPOS' }
  ];

  selectedWatchlist: string = this.watchlists[0].value;
  todayString: string;
  selectedDay: string;
  focusedIndex = 0;
  hasUserNavigated = false;

  historyStartDate: string;
  historyEndDate: string;
  watchlistHistoryData: WatchlistHistoryEntry[] = [];
  watchlistHistoryLoading = false;
  watchlistHistoryError: string | null = null;

  historyChartGroups: WatchlistChartGroup[] = [
    {
      id: 'sevenPct',
      title: '7% Movers',
      datasets: [
        {
          watchlist: '7_PCT_UP',
          label: '7% Up',
          backgroundColor: 'rgba(36, 192, 111, 0.6)',
          borderColor: 'rgba(36, 192, 111, 1)'
        },
        {
          watchlist: '7_PCT_DOWN',
          label: '7% Down',
          backgroundColor: 'rgba(255, 99, 132, 0.6)',
          borderColor: 'rgba(255, 99, 132, 1)'
        }
      ]
    },
    {
      id: 'fiftyTwoWeek',
      title: '52 Week High vs Low',
      datasets: [
        {
          watchlist: 'NEW_52_WEEK_HIGH',
          label: '52 Week High',
          backgroundColor: 'rgba(36, 192, 111, 0.6)',
          borderColor: 'rgba(36, 192, 111, 1)'
        },
        {
          watchlist: 'NEW_52_WEEK_LOW',
          label: '52 Week Low',
          backgroundColor: 'rgba(255, 99, 132, 0.6)',
          borderColor: 'rgba(255, 99, 132, 1)'
        }
      ]
    },
    {
      id: 'sepa',
      title: 'SEPA Watchlist',
      datasets: [
        {
          watchlist: 'SEPA',
          label: 'SEPA',
          backgroundColor: 'rgba(54, 162, 235, 0.6)',
          borderColor: 'rgba(54, 162, 235, 1)'
        }
      ]
    }
  ];

  @ViewChildren('historyChartCanvas')
  historyChartCanvases!: QueryList<ElementRef<HTMLCanvasElement>>;

  @ViewChildren('stockCard')
  stockCards!: QueryList<ElementRef<HTMLDivElement>>;

  @ViewChildren('financialChartCanvas')
  financialChartCanvases!: QueryList<ElementRef<HTMLCanvasElement>>;

  private historyCharts = new Map<string, Chart>();
  private financialCharts = new Map<string, Chart>();
  
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
    this.historyEndDate = this.todayString;
    const thirtyDaysAgo = this.addDays(new Date(), -29);
    this.historyStartDate = this.formatDate(thirtyDaysAgo);
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

    this.historyChartCanvases.changes.subscribe(list => {
      if (!list.length) {
        this.destroyHistoryCharts();
        return;
      }

      this.renderHistoryCharts();
    });

    this.financialChartCanvases.changes.subscribe(() => {
      this.renderFinancialCharts();
    });

    setTimeout(() => this.renderFinancialCharts());
  }

  selectTab(tab: 'charts' | 'breadth' | 'history'): void {
    this.selectedTab = tab;

    if (tab === 'breadth' && !this.breadthIndicators.length) {
      this.loadBreadthIndicators();
    }

    if (tab === 'history') {
      if (!this.watchlistHistoryData.length && !this.watchlistHistoryLoading) {
        this.loadWatchlistHistory();
      } else {
        setTimeout(() => this.renderHistoryCharts());
      }
    }
  }

  loadWatchlist(watchlist: string): void {
    this.selectedWatchlist = watchlist;
    this.symbols = [];
    this.descriptions = {};
    this.stats = {};
    this.financials = {};
    this.focusedIndex = 0;
    this.hasUserNavigated = false;
    this.destroyFinancialCharts();

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

      this.loadFinancialsForSymbols(this.symbols);
    });
  }

  loadWatchlistHistory(): void {
    if (this.watchlistHistoryLoading) {
      return;
    }

    this.watchlistHistoryError = null;

    if (!this.historyStartDate || !this.historyEndDate) {
      this.watchlistHistoryError = 'Please select both a start and end date.';
      return;
    }

    if (this.historyStartDate > this.historyEndDate) {
      this.watchlistHistoryError = 'Start date must be on or before the end date.';
      return;
    }

    this.watchlistHistoryLoading = true;
    this.watchlistHistoryData = [];
    this.destroyHistoryCharts();

    this.http
      .get<WatchlistHistoryEntry[]>(
        `/watchlists/history?start=${this.historyStartDate}&end=${this.historyEndDate}`
      )
      .subscribe({
        next: data => {
          const sortedData = [...data].sort((a, b) => (a.day > b.day ? 1 : a.day < b.day ? -1 : 0));
          const filteredData = sortedData.filter(entry => !this.isWeekend(entry.day));
          const finalData = filteredData.length ? filteredData : sortedData;
          this.watchlistHistoryData = finalData;

          setTimeout(() => this.renderHistoryCharts());
        },
        error: () => {
          this.watchlistHistoryError = 'Unable to load watchlist history. Please try again later.';
          this.watchlistHistoryData = [];
          this.watchlistHistoryLoading = false;
          this.destroyHistoryCharts();
        },
        complete: () => {
          this.watchlistHistoryLoading = false;
        }
      });
  }

  onHistoryStartChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.historyStartDate = input.value || this.historyStartDate;

    if (this.historyStartDate > this.historyEndDate) {
      this.historyEndDate = this.historyStartDate;
    }
  }

  onHistoryEndChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.historyEndDate = input.value || this.historyEndDate;

    if (this.historyEndDate < this.historyStartDate) {
      this.historyStartDate = this.historyEndDate;
    }
  }

  onWatchlistChange(event: Event): void {
    const selectedWatchlist = (event.target as HTMLSelectElement).value;
    this.loadWatchlist(selectedWatchlist);
  }

  private destroyHistoryCharts(): void {
    this.historyCharts.forEach(chart => chart.destroy());
    this.historyCharts.clear();
  }

  private isWeekend(dateString: string): boolean {
    const parsed = new Date(`${dateString}T00:00:00Z`);

    if (Number.isNaN(parsed.getTime())) {
      return false;
    }

    const day = parsed.getUTCDay();
    return day === 0 || day === 6;
  }

  private renderHistoryCharts(): void {
    if (!this.watchlistHistoryData.length || !this.historyChartCanvases?.length) {
      return;
    }

    const labels = this.watchlistHistoryData.map(entry => entry.day);
    this.historyChartCanvases.forEach(canvasRef => {
      const canvas = canvasRef.nativeElement;
      const chartId = canvas.dataset['chartId'];
      if (!chartId) {
        return;
      }

      const config = this.historyChartGroups.find(group => group.id === chartId);
      if (!config) {
        return;
      }

      const context = canvas.getContext('2d');
      if (!context) {
        return;
      }

      const datasets = config.datasets.map(dataset => ({
        label: dataset.label,
        data: this.watchlistHistoryData.map(entry => {
          const summary = entry.watchlists.find(w => w.name === dataset.watchlist);
          return summary ? summary.symbolCount : 0;
        }),
        backgroundColor: dataset.backgroundColor,
        borderColor: dataset.borderColor,
        borderWidth: 1,
        maxBarThickness: 32
      }));

      const existingChart = this.historyCharts.get(chartId);
      if (existingChart) {
        existingChart.destroy();
      }

      const chart = new Chart(context, {
        type: 'bar',
        data: {
          labels,
          datasets
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          layout: {
            padding: {
              top: 10,
              right: 14,
              bottom: 10,
              left: 14
            }
          },
          scales: {
            x: {
              stacked: false,
              ticks: {
                color: '#ffffff',
                maxRotation: 30,
                minRotation: 0,
                autoSkip: true,
                autoSkipPadding: 12,
                font: {
                  size: 10
                }
              },
              grid: {
                color: 'rgba(255, 255, 255, 0.08)'
              },
              title: {
                display: true,
                text: 'Date',
                color: '#ffffff'
              }
            },
            y: {
              beginAtZero: true,
              ticks: {
                color: '#ffffff',
                precision: 0
              },
              grid: {
                color: 'rgba(255, 255, 255, 0.08)'
              },
              title: {
                display: true,
                text: 'Symbols',
                color: '#ffffff'
              }
            }
          },
          plugins: {
            legend: {
              position: 'top',
              labels: {
                color: '#ffffff',
                padding: 12,
                boxWidth: 16
              }
            },
            tooltip: {
              mode: 'index',
              intersect: false
            }
          }
        }
      });

      this.historyCharts.set(chartId, chart);
    });
  }

  private loadFinancialsForSymbols(symbols: string[]): void {
    this.destroyFinancialCharts();

    const uniqueSymbols = Array.from(new Set(symbols)).filter(Boolean);
    if (!uniqueSymbols.length) {
      this.financials = {};
      return;
    }

    const encodedSymbols = uniqueSymbols.map(symbol => encodeURIComponent(symbol)).join(',');
    this.http
      .get<Record<string, StockFinancials>>(`/financials?tickers=${encodedSymbols}`)
      .subscribe({
        next: response => {
          const nextFinancials: { [ticker: string]: AnnualFinancialData[] } = {};

          Object.entries(response).forEach(([ticker, data]) => {
            if (!data?.annual?.length) {
              nextFinancials[ticker] = [];
              return;
            }

            const trimmed = [...data.annual]
              .sort((a, b) => b.year - a.year)
              .slice(0, 5)
              .sort((a, b) => a.year - b.year);

            nextFinancials[ticker] = trimmed;
          });

          uniqueSymbols.forEach(symbol => {
            if (!nextFinancials[symbol]) {
              nextFinancials[symbol] = [];
            }
          });

          this.financials = nextFinancials;

          setTimeout(() => this.renderFinancialCharts());
        },
        error: () => {
          this.financials = {};
          this.destroyFinancialCharts();
        }
      });
  }

  private renderFinancialCharts(): void {
    if (!this.financialChartCanvases?.length) {
      return;
    }

    this.financialChartCanvases.forEach(canvasRef => {
      const canvas = canvasRef.nativeElement;
      const symbol = canvas.dataset['symbol'];
      if (!symbol) {
        return;
      }

      const data = this.financials[symbol];
      const existing = this.financialCharts.get(symbol);

      if (!data?.length) {
        if (existing) {
          existing.destroy();
          this.financialCharts.delete(symbol);
        }
        return;
      }

      const context = canvas.getContext('2d');
      if (!context) {
        return;
      }

      if (existing) {
        existing.destroy();
      }

      const labels = data.map(entry => entry.year.toString());
      const chart = new Chart(context, {
        type: 'bar',
        data: {
          labels,
          datasets: [
            {
              label: 'Revenue per Share ($)',
              data: data.map(entry => entry.revenuePerShare ?? null),
              backgroundColor: 'rgba(54, 162, 235, 0.6)',
              borderColor: 'rgba(54, 162, 235, 1)',
              borderWidth: 1,
              maxBarThickness: 26,
              yAxisID: 'yDollar'
            },
            {
              label: 'Basic EPS ($)',
              data: data.map(entry => entry.basicEarningsPerShare ?? null),
              backgroundColor: 'rgba(75, 192, 192, 0.6)',
              borderColor: 'rgba(75, 192, 192, 1)',
              borderWidth: 1,
              maxBarThickness: 26,
              yAxisID: 'yDollar'
            },
            {
              label: 'Operating Margin (%)',
              data: data.map(entry =>
                entry.operatingMargin !== undefined && entry.operatingMargin !== null
                  ? entry.operatingMargin * 100
                  : null
              ),
              backgroundColor: 'rgba(255, 159, 64, 0.6)',
              borderColor: 'rgba(255, 159, 64, 1)',
              borderWidth: 1,
              maxBarThickness: 26,
              yAxisID: 'yPercent'
            },
            {
              label: 'Net Income Margin (%)',
              data: data.map(entry =>
                entry.netIncomeMargin !== undefined && entry.netIncomeMargin !== null
                  ? entry.netIncomeMargin * 100
                  : null
              ),
              backgroundColor: 'rgba(255, 99, 132, 0.6)',
              borderColor: 'rgba(255, 99, 132, 1)',
              borderWidth: 1,
              maxBarThickness: 26,
              yAxisID: 'yPercent'
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          layout: {
            padding: {
              top: 6,
              right: 10,
              bottom: 6,
              left: 10
            }
          },
          scales: {
            x: {
              ticks: {
                color: '#ffffff',
                font: {
                  size: 9
                }
              },
              grid: {
                color: 'rgba(255, 255, 255, 0.08)'
              }
            },
            yDollar: {
              position: 'left',
              beginAtZero: true,
              ticks: {
                color: '#ffffff',
                callback: value => `$${value}`
              },
              grid: {
                color: 'rgba(255, 255, 255, 0.08)'
              }
            },
            yPercent: {
              position: 'right',
              beginAtZero: true,
              ticks: {
                color: '#ffffff',
                callback: value => `${value}%`
              },
              grid: {
                drawOnChartArea: false,
                color: 'rgba(255, 255, 255, 0.12)'
              }
            }
          },
          plugins: {
            legend: {
              position: 'bottom',
              labels: {
                color: '#ffffff',
                font: {
                  size: 9
                }
              }
            },
            tooltip: {
              mode: 'index',
              intersect: false
            }
          }
        }
      });

      this.financialCharts.set(symbol, chart);
    });
  }

  private destroyFinancialCharts(): void {
    this.financialCharts.forEach(chart => chart.destroy());
    this.financialCharts.clear();
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
      case 'bull_34_13':
      case 'bear_34_13': {
        const bull = indicator.bull_34_13;
        const bear = indicator.bear_34_13;

        if (typeof bull === 'number' && typeof bear === 'number') {
          const isBullish = bull > bear;
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

  private addDays(date: Date, offset: number): Date {
    const copy = new Date(date);
    copy.setDate(copy.getDate() + offset);
    return copy;
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

  ngOnDestroy(): void {
    this.destroyHistoryCharts();
    this.destroyFinancialCharts();
  }
}
