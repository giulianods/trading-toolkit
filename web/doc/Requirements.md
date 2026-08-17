# Greensheep Stocks
 
A tool to visualize the stock charts and wathchlists.


### General concepts

In the Greensheep Stocks realm, there are a few key concepts that are used:

1. Stock Universe - a set of stocks that are grouped together ususally geographically (e.g. 'us-stocks', 'canada-stocks'),  but other criteria may be employed as well. Currently we will only  have 'us-stocks' by default but in the future we will like to also add other universes. A stock is uniquely referenced by a ticker symbol (e.g. 'AAPL')

2. Stock Market Data - a set of price data for the stocks in the universe, only ohlc daily data is of concern to us

3. Stock Financial Data - a set of annual and quarterly reported financial metrics (eps, ebitda, etc.)

4. Watchlist - a set of stocks within a universe that satisfy a common criteria


### Project description

This app is a web browser based visualization tool for watchlists and charts of a stock universe.
The data is provided by a rest service and this app needs to define the front end.
How the front end looks is not strictly defined, however a few important considerations need to be made
 - I want to be able to browse watchlists (the default watchlist I want to  be displayed is 'SEPA')
 - I want to be able to see charts from the watchlist as barcharts (OHLC). This must be very  efficient since hundreds of charts can be in a watchlist
 - don't use the svg chart functionality from the rest service, create your own barcharts with the best library you see fir for the job
 - watchlists are a "per-day" basis, meaning they are a result of a screen , and so you can query historical watchlists, but this is a future task
 - a watchlist is an ordered set of tickers and I want to  be able to navigate using the (up arrow) and (down arrow) keys
 - depending on how large the generated chart is you might find it fit to arrange multiple charts per rows (in this case left and right arrows can navigate)
 - the chart needs to provide an overview of the price action, so not too detailed, and around 150 days of bars need to be displayed
 - you can find the rest server spec in the project 'doc\openapi.yaml'