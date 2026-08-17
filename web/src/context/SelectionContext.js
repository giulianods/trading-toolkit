import React, { createContext, useContext } from 'react';

export const SelectionContext = createContext({
  selectedTickers: new Set(),
  onSelectionChange: () => {},
});

export function useSelection() {
  return useContext(SelectionContext);
}
