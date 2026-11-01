import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as render_ from '../lib/cardRender';
import * as pdf from '../lib/sheetPdf';
import { DEFAULT_PLACEMENT } from '../lib/cardDesign';
import SheetExport, { type SheetSideInput } from './SheetExport';

afterEach(() => vi.restoreAllMocks());

const cards = (n: number) => Array.from({ length: n }, (_, i) => ({ serial: `TOMS-${String(i + 1).padStart(4, '0')}`, card_uuid: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}` }));
const side = (over: Partial<SheetSideInput> = {}): SheetSideInput => ({
  file: new File(['x'], 'front.png', { type: 'image/png' }),
  image: {} as HTMLImageElement,
  width: 1011,
  height: 638,
  src: 'blob:front',
  placement: DEFAULT_PLACEMENT,
  ...over,
});

const renderIt = (over: Partial<React.ComponentProps<typeof SheetExport>> = {}) =>
  render(<SheetExport cards={cards(20)} baseUrl="https://toms.example.com" front={side()} back={undefined} {...over} />);

describe('SheetExport', () => {
  it('tells how many cards fit and how many sheets are needed', () => {
    renderIt({ cards: cards(20) });
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('9 cards per sheet');
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('20 cards need 3 sheets');
  });

  it('draws one card per slot in the preview', () => {
    renderIt({ cards: cards(5) });
    expect(screen.getAllByTestId('sheet-card')).toHaveLength(5);
  });

  it('shows empty slots in the preview before any card exists', () => {
    renderIt({ cards: [] });
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('9 cards per sheet');
    expect(screen.queryAllByTestId('sheet-card')).toHaveLength(0);
  });

  it('recomputes when the paper changes to Letter', async () => {
    renderIt();
    await userEvent.selectOptions(screen.getByLabelText('Paper'), 'letter');
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('8 cards per sheet');
  });

  it('accepts a custom paper size', async () => {
    renderIt();
    await userEvent.selectOptions(screen.getByLabelText('Paper'), 'custom');
    fireEvent.change(screen.getByLabelText('Paper width'), { target: { value: '150' } });
    fireEvent.change(screen.getByLabelText('Paper height'), { target: { value: '220' } });
    await userEvent.selectOptions(screen.getByLabelText('Paper direction'), 'portrait');
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('portrait 150 × 220');
  });

  it('shows more room between cards as fewer cards per sheet', () => {
    renderIt();
    fireEvent.change(screen.getByLabelText('Space between cards'), { target: { value: '20' } });
    const text = screen.getByTestId('sheet-summary').textContent ?? '';
    expect(Number(/(\d+) cards per sheet/.exec(text)?.[1])).toBeLessThan(9);
  });

  it('explains a card that is too big for the paper and blocks the download', () => {
    renderIt();
    fireEvent.change(screen.getByLabelText('Card width'), { target: { value: '400' } });
    expect(screen.getByRole('alert')).toHaveTextContent('does not fit');
    expect(screen.getByRole('button', { name: /Download PDF/ })).toBeDisabled();
  });

  it('blocks the download until a front design and cards exist', () => {
    renderIt({ front: undefined });
    expect(screen.getByText('Upload the front design first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Download PDF/ })).toBeDisabled();
  });

  it('offers the flip setting and front/back preview only when there is a back design', () => {
    const { unmount } = renderIt();
    expect(screen.queryByLabelText('Flip edge')).not.toBeInTheDocument();
    unmount();
    renderIt({ back: side({ src: 'blob:back', placement: null }) });
    expect(screen.getByLabelText('Flip edge')).toBeInTheDocument();
    expect(screen.getByTestId('sheet-summary')).toHaveTextContent('6 pages with the backs');
  });

  it('warns when the card shape differs from the design and can match it', async () => {
    renderIt({ front: side({ width: 1000, height: 1000 }) });
    await userEvent.click(screen.getByRole('button', { name: /Card shape differs/ }));
    expect(screen.getByLabelText('Card height')).toHaveValue(85.6);
  });

  it('builds the PDF with the chosen layout and saves it by serial range', async () => {
    const build = vi.spyOn(pdf, 'buildSheetPdf').mockResolvedValue(new Uint8Array([37, 80, 68, 70]));
    const save = vi.spyOn(render_, 'saveBlob').mockImplementation(() => undefined);
    vi.spyOn(await import('../lib/pdfArt'), 'toPdfArt').mockResolvedValue({ bytes: new Uint8Array(), kind: 'png', width: 1, height: 1 });
    renderIt({ cards: cards(12) });
    await userEvent.click(screen.getByRole('button', { name: /Download PDF/ }));
    await vi.waitFor(() => expect(save).toHaveBeenCalled());
    expect(build).toHaveBeenCalledWith(expect.objectContaining({ flip: 'long', baseUrl: 'https://toms.example.com' }));
    expect(save).toHaveBeenCalledWith(expect.any(Blob), 'TOMS-0001_to_TOMS-0012_sheets.pdf');
  });

  it('shows the error when building fails', async () => {
    vi.spyOn(pdf, 'buildSheetPdf').mockRejectedValue(new Error('This image cannot be placed in a PDF.'));
    vi.spyOn(await import('../lib/pdfArt'), 'toPdfArt').mockResolvedValue({ bytes: new Uint8Array(), kind: 'png', width: 1, height: 1 });
    renderIt();
    await userEvent.click(screen.getByRole('button', { name: /Download PDF/ }));
    expect(await screen.findByText(/cannot be placed in a PDF/)).toBeInTheDocument();
  });
});
