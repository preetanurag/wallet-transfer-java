import React from 'react';
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import App from '../src/App.jsx';
import { api } from '../src/api.js';
vi.mock('../src/api.js', () => ({ api: vi.fn(), explain: x => x }));
const alice='00000000-0000-4000-8000-000000000001', bob='00000000-0000-4000-8000-000000000002';
const transfer={id:'00000000-0000-4000-8000-000000000010',from:alice,to:bob,amount_paise:1234,status:'succeeded'};
beforeEach(() => api.mockReset().mockImplementation(async path => ({value: path === '/healthz' ? {status:'UP'} : {id:alice,balance_paise:100000}})));
async function connect() {
  render(<App/>); fireEvent.change(screen.getByLabelText('Bearer token'),{target:{value:'test-token'}});
  fireEvent.click(screen.getByRole('button',{name:'Connect'}));
  await screen.findByText('Your wallet is connected');
}
async function review() {
  await connect();
  fireEvent.change(screen.getByLabelText('Recipient wallet ID'),{target:{value:bob}});
  fireEvent.change(screen.getByLabelText('Amount'),{target:{value:'12.34'}});
  fireEvent.click(screen.getByRole('button',{name:'Review transfer'}));
}
it('reviews before sending and converts rupees to paise', async () => {
  await review(); expect(api.mock.calls.filter(c=>c[0]==='/transfers')).toHaveLength(0);
  api.mockResolvedValueOnce({value:transfer});
  fireEvent.click(screen.getByRole('button',{name:'Confirm transfer'}));
  await screen.findByRole('heading',{name:'Money sent'});
  expect(api).toHaveBeenCalledWith('/transfers','test-token',expect.objectContaining({from:alice,to:bob,amount_paise:1234}));
});
it('retains the identical request after an uncertain response', async () => {
  await review(); api.mockRejectedValueOnce(Object.assign(new Error('Network lost'),{uncertain:true}));
  fireEvent.click(screen.getByRole('button',{name:'Confirm transfer'}));
  await screen.findByRole('button',{name:'Retry same request'});
  const first=api.mock.calls.find(c=>c[0]==='/transfers');
  api.mockResolvedValueOnce({value:transfer});
  fireEvent.click(screen.getByRole('button',{name:'Retry same request'}));
  await screen.findByRole('heading',{name:'Money sent'});
  expect(api.mock.calls.filter(c=>c[0]==='/transfers')).toEqual([first,first]);
});
it('shows a persistent decline as a decline', async () => {
  await review(); api.mockResolvedValueOnce({value:{...transfer,status:'declined',reason:'insufficient_funds'}});
  fireEvent.click(screen.getByRole('button',{name:'Confirm transfer'}));
  await screen.findByRole('heading',{name:'Transfer declined'});
});
it('offers refunds only for an incoming successful original transfer', async () => {
  await connect();
  fireEvent.change(screen.getByLabelText('Transfer ID'),{target:{value:transfer.id}});
  api.mockResolvedValueOnce({value:transfer}); fireEvent.click(screen.getByRole('button',{name:'Look up'}));
  await screen.findByText('Only the recipient can issue a refund.');
  expect(screen.queryByRole('button',{name:'Review refund'})).not.toBeInTheDocument();
  api.mockResolvedValueOnce({value:{...transfer,from:bob,to:alice}}); fireEvent.click(screen.getByRole('button',{name:'Look up'}));
  fireEvent.click(await screen.findByRole('button',{name:'Review refund'}));
  api.mockResolvedValueOnce({value:{...transfer,reversal_of:transfer.id}});
  fireEvent.click(screen.getByRole('button',{name:'Confirm refund'}));
  await screen.findByRole('heading',{name:'Refund completed'});
  expect(api).toHaveBeenCalledWith(`/transfers/${transfer.id}/reverse`,'test-token',expect.objectContaining({idempotency_key:expect.any(String)}));
});
it('rejects excessive decimal places before creating a request',async()=>{
 await connect(); fireEvent.change(screen.getByLabelText('Recipient wallet ID'),{target:{value:bob}});
 fireEvent.change(screen.getByLabelText('Amount'),{target:{value:'1.001'}});
 fireEvent.click(screen.getByRole('button',{name:'Review transfer'}));
 expect(await screen.findByRole('alert')).toHaveTextContent('two decimal places');
 expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
