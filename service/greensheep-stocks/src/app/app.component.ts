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

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  symbols: string[] = [];
  descriptions: { [symbol: string]: StockDescription } = {};

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.http.get('/watchlists/SEPA', { responseType: 'text' }).subscribe(data => {
      this.symbols = data.split('\n').filter(s => s.trim().length > 0);
      this.symbols.forEach(s => {
        this.http.get<StockDescription>(`/description/${s}`).subscribe(desc => {
          this.descriptions[s] = desc;
        });
      });
    });
  }

  chartUrl(symbol: string): string {
    return `/chart/${symbol}?days=200`;
  }
}
