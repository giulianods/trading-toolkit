import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  title = 'greensheep-stocks';
  symbols: string[] = [];

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.http.get('/watchlists/SEPA', { responseType: 'text' }).subscribe(data => {
      this.symbols = data.split('\n').filter(s => s.trim().length > 0);
    });
  }

  chartUrl(symbol: string): string {
    return `/chart/${symbol}?days=200`;
  }
}
