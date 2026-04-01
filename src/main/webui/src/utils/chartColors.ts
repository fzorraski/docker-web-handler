export const CHART_COLORS = [
  '#FF6D00', '#00BCD4', '#8BC34A', '#E91E63', '#FFC107',
  '#9C27B0', '#03A9F4', '#FF5722', '#4CAF50', '#673AB7',
]

export function chartColor(index: number): string {
  return CHART_COLORS[index % CHART_COLORS.length]
}
