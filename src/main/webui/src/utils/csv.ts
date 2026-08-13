/** Values a CSV cell can hold before it is rendered as text. */
export type CsvValue = string | number | null | undefined

/**
 * Renders rows as RFC 4180 CSV.
 *
 * Cells that begin with a formula character are prefixed with a quote. Actor
 * names in the audit trail come from whoever typed them at the login form, so
 * an export of failed sign-ins is exactly the file where a hostile value would
 * land - and spreadsheets execute those on open.
 */
export function toCsv(headers: string[], rows: CsvValue[][]): string {
  return [headers, ...rows].map(row => row.map(escape).join(',')).join('\r\n')
}

function escape(value: CsvValue): string {
  if (value === null || value === undefined) return ''
  let text = String(value)
  // numbers are ours, not the audit trail's - guarding them would turn a
  // negative count into text and break every sum in the spreadsheet
  if (typeof value === 'string' && /^[=+\-@\t\r]/.test(text)) {
    text = `'${text}`
  }
  if (/[",\r\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

/** Offers the CSV to the browser as a download. */
export function downloadCsv(filename: string, csv: string): void {
  // the BOM makes Excel read it as UTF-8 instead of the local codepage
  const blob = new Blob(['﻿', csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
