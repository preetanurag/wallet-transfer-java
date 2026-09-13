import React, { useEffect, useRef, useState } from 'react';
import { ArrowUpRight, ArrowDownLeft, ArrowRight, Wallet, LayoutDashboard, Search, ShieldCheck, LogOut, RefreshCw, Copy, Check, X, ChevronRight, KeyRound, CircleHelp, ExternalLink, RotateCcw } from 'lucide-react';
import { api, explain } from './api.js';
import { formatMoney, parseRupees, validId } from './money.js';

const short = id => id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '—';
const statusLabel = status => status === 'succeeded' ? 'Completed' : 'Declined';
const demoAccounts = import.meta.env.DEV ? [
  ['Alice', 'demo-alice-token-0001'],
  ['Bob', 'demo-bob-token-000001'],
  ['Carol', 'demo-carol-token-0001'],
] : [
  ['Alice', 'E-nm_NP4Yf2okAH0diFrEc53IKXscU6q'],
  ['Bob', 'RHhLpsMwaV5aWkMj24gNZOZYSStibc33'],
  ['Carol', '9bLZTjROb-6tPFZC2wJo8r4hr4ycAb9B'],
  ['Fresh', 'G11eVFbZupLbg2V5vnSOFRHRIre37tXa'],
];
function CopyId({ value, label = 'Copy ID' }) {
  const [copied, setCopied] = useState(false);
  return <button className="icon-button" title={label} aria-label={label} onClick={async () => {
    try { await navigator.clipboard.writeText(value); setCopied(true); setTimeout(() => setCopied(false), 1800); } catch { setCopied(false); }
  }}>{copied ? <Check size={15}/> : <Copy size={15}/>}</button>;
}
export default function App() {
  const [session, setSession] = useState(null);
  const [token, setToken] = useState('');
  const [wallet, setWallet] = useState(null);
  const [health, setHealth] = useState('checking');
  const [connecting, setConnecting] = useState(false);
  const [notice, setNotice] = useState(null);
  const [recipient, setRecipient] = useState('');
  const [amount, setAmount] = useState('');
  const [lookupId, setLookupId] = useState('');
  const [lookupBusy, setLookupBusy] = useState(false);
  const [details, setDetails] = useState(null);
  const [activity, setActivity] = useState([]);
  const [operation, setOperation] = useState(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const gate = useRef(false);
  const sessionEpoch = useRef(0);
  const connectRef = useRef(null);
  const transferRef = useRef(null);
  const lookupRef = useRef(null);
  const modalRef = useRef(null);
  const returnFocus = useRef(null);
  const unresolved = operation?.phase === 'uncertain';
  const modalOpen = Boolean(operation);
  const frozen = busy || unresolved;

  useEffect(() => { api('/healthz').then(() => setHealth('online')).catch(() => setHealth('offline')); }, []);
  useEffect(() => {
    if (!modalOpen) return;
    returnFocus.current = document.activeElement;
    modalRef.current?.focus();
    return () => returnFocus.current?.focus?.();
  }, [modalOpen]);
  function inform(message, type = 'error') { setNotice({ message, type }); }
  async function connect(credential, name = 'Your wallet') {
    if (gate.current || frozen || connecting) return;
    if (!credential.trim()) { inform('Enter your bearer token to connect.'); return; }
    const epoch = ++sessionEpoch.current;
    setConnecting(true); setNotice(null);
    try {
      const { value } = await api('/wallets', credential.trim(), {});
      if (epoch !== sessionEpoch.current) return;
      setSession({ token: credential.trim(), name }); setWallet(value); setToken('');
      setActivity([]); setDetails(null); setOperation(null); setHealth('online');
      setRecipient(''); setAmount(''); setLookupId(''); setLookupBusy(false); setRefreshing(false);
    } catch (error) { if (epoch === sessionEpoch.current) inform(error.message); }
    finally { if (epoch === sessionEpoch.current) setConnecting(false); }
  }
  function disconnect() {
    if (frozen) return;
    sessionEpoch.current++; setLookupBusy(false); setRefreshing(false); setConnecting(false); setRecipient(''); setAmount(''); setLookupId(''); setSession(null); setWallet(null); setDetails(null); setActivity([]); setOperation(null); setNotice(null);
  }
  async function refresh() {
    if (!session || refreshing) return;
    const epoch = sessionEpoch.current;
    setRefreshing(true);
    try { const result = await api(`/wallets/${wallet.id}`, session.token); if (epoch === sessionEpoch.current) setWallet(result.value); }
    catch { if (epoch === sessionEpoch.current) inform('Balance could not be refreshed. Try Refresh again.'); }
    finally { if (epoch === sessionEpoch.current) setRefreshing(false); }
  }
  function review(event) {
    event.preventDefault(); if (!session || frozen || gate.current) return;
    try {
      if (!validId(recipient.trim())) throw new Error('Enter a valid recipient wallet ID.');
      if (recipient.trim().toLowerCase() === wallet.id) throw new Error('Choose a different wallet to send money to.');
      const paise = parseRupees(amount);
      setNotice(null);
      setOperation({ kind: 'transfer', phase: 'review', path: '/transfers', body: { from: wallet.id, to: recipient.trim().toLowerCase(), amount_paise: paise, idempotency_key: crypto.randomUUID() } });
    } catch (error) { inform(error.message); }
  }
  function reviewRefund(t) {
    if (frozen) return;
    setNotice(null);
    setOperation({ kind: 'refund', phase: 'review', amount: t.amount_paise, to: t.from, path: `/transfers/${t.id}/reverse`, body: { idempotency_key: crypto.randomUUID() } });
  }
  async function execute() {
    if (!operation || !session || gate.current) return;
    gate.current = true; setBusy(true);
    const pending = operation;
    try {
      const result = await api(pending.path, session.token, pending.body);
      setOperation({ ...pending, phase: 'done', result: result.value, correlation: result.correlation });
      setDetails(result.value);
      setActivity(rows => [result.value, ...rows.filter(row => row.id !== result.value.id)]);
      await refresh();
    } catch (error) {
      setOperation({ ...pending, phase: error.uncertain ? 'uncertain' : 'error', error: error.message, correlation: error.correlation });
    } finally { gate.current = false; setBusy(false); }
  }
  async function lookup(event) {
    event.preventDefault(); if (!session || lookupBusy || frozen) return;
    if (!validId(lookupId.trim())) { inform('Enter a valid transfer ID.'); return; }
    const epoch = sessionEpoch.current;
    setLookupBusy(true); setNotice(null);
    try {
      const { value } = await api(`/transfers/${lookupId.trim()}`, session.token);
      if (epoch !== sessionEpoch.current) return;
      setDetails(value); setActivity(rows => [value, ...rows.filter(row => row.id !== value.id)]);
    } catch (error) { if (epoch === sessionEpoch.current) { setDetails(null); inform(error.message); } }
    finally { if (epoch === sessionEpoch.current) setLookupBusy(false); }
  }
  function closeModal() { if (!frozen) setOperation(null); }
  function modalKeys(event) {
    if (event.key === 'Escape') closeModal();
    if (event.key === 'Tab') {
      const items = [...modalRef.current.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href]')];
      if (!items.length) { event.preventDefault(); return; }
      const first = items[0], last = items.at(-1);
      if (event.shiftKey && (document.activeElement === first || document.activeElement === modalRef.current)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  }
  const opAmount = operation?.body.amount_paise ?? operation?.amount;
  const opTo = operation?.body.to ?? operation?.to;
  return <>
    <div className="app-shell" inert={modalOpen ? true : undefined}>
      <aside className="sidebar">
        <a className="brand" href="#" aria-label="Paisa home"><span className="brand-mark">p<span>•</span></span><span>paisa<span className="brand-dot">.</span></span></a>
        <div className="workspace-label">YOUR WORKSPACE</div>
        <nav aria-label="Main navigation">
          <button className="nav-item selected" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}><LayoutDashboard size={19}/>Overview<span className="nav-dot"/></button>
          <button className="nav-item" onClick={() => transferRef.current?.focus()}><ArrowUpRight size={20}/>Send money</button>
          <button className="nav-item" onClick={() => lookupRef.current?.focus()}><Search size={19}/>Find a transfer</button>
        </nav>
        <div className="sidebar-bottom"><div className="safe-icon"><ShieldCheck size={23}/></div><strong>A little peace of mind.</strong><p>Transfers and refunds are recorded together with your balance.</p><span className="demo-label">DEMO WORKSPACE</span></div>
        <div className="sidebar-footer"><span className="tiny-dot"/>Made for the small things.</div>
      </aside>
      <main>
        <header className="topbar"><span>Workspace <ChevronRight size={14}/> <b>Overview</b></span><div className="topbar-right"><span className={`connection ${health}`}><i/>{health === 'online' ? 'Service online' : health === 'checking' ? 'Connecting…' : 'Service unavailable'}</span><span className="avatar">{session ? session.name[0] : 'P'}</span></div></header>
        <div className="main-content">
          <section className="page-heading"><div><div className="eyebrow">A CLEARER VIEW OF YOUR MONEY</div><h1>{session ? `Hello, ${session.name === 'Your wallet' ? 'there' : session.name}.` : 'Your wallet starts here.'}</h1><p>Send with confidence. Keep every transfer in view.</p></div><span className="outline-badge"><ShieldCheck size={15}/> Local demo · no real money</span></section>
          {notice && <div className={`notice ${notice.type}`} role="alert"><span>{notice.message}</span><button aria-label="Dismiss message" className="icon-button" onClick={() => setNotice(null)}><X size={16}/></button></div>}
          <section className="connect-panel" ref={connectRef} aria-label="Wallet connection">
            <div className="connect-heading"><span className="small-icon"><KeyRound size={18}/></span><div><strong>{session ? `${session.name} is connected` : 'Connect your wallet'}</strong><p>{session ? 'Your token stays in memory for this session.' : 'Use your bearer token to securely open your wallet.'}</p></div></div>
            {session ? <button className="text-button" disabled={frozen} onClick={disconnect}><LogOut size={16}/>Disconnect</button> : <form onSubmit={e => { e.preventDefault(); connect(token); }} className="token-form"><label className="sr-only" htmlFor="token">Bearer token</label><input id="token" type="password" value={token} onChange={e => setToken(e.target.value)} placeholder="Paste your bearer token" autoComplete="off" disabled={connecting}/><button className="button primary" disabled={connecting}>{connecting ? 'Connecting…' : 'Connect'}<ArrowRight size={16}/></button></form>}
            <div className="demo-switch"><span>Try a demo account:</span>{demoAccounts.map(([name, credential]) => <button key={name} disabled={connecting || frozen || session?.name === name} onClick={() => connect(credential, name)}>{name}</button>)}<small>{import.meta.env.DEV ? 'Local seeded tokens.' : 'Live reviewer tokens.'} Switching clears this session’s activity.</small></div>
          </section>
          <div className="dashboard-grid">
            <section className="balance-card" aria-label="Wallet balance"><div className="balance-top"><span><Wallet size={18}/>Available balance</span><span className="currency-chip">INR</span></div><div className="balance-amount" data-testid="balance">{wallet ? formatMoney(wallet.balance_paise) : '₹ —'}</div><p>{wallet ? 'Ready when you are.' : 'Connect a wallet to see your balance.'}</p><div className="balance-bottom"><div><span className="balance-id-label">WALLET ID</span><div className="id-line"><code title={wallet?.id}>{short(wallet?.id)}</code>{wallet && <CopyId value={wallet.id} label="Copy wallet ID"/>}</div></div><button className="refresh-button" disabled={!session || refreshing || busy} onClick={refresh}><RefreshCw size={15} className={refreshing ? 'spinning' : ''}/>{refreshing ? 'Refreshing' : 'Refresh'}</button></div><span className="card-orbit orbit-one"/><span className="card-orbit orbit-two"/></section>
            <section className="panel send-panel"><div className="section-title"><span className="small-icon"><ArrowUpRight size={19}/></span><div><h2>Send money</h2><p>A few details. One simple transfer.</p></div></div><form onSubmit={review}><label htmlFor="recipient">Recipient wallet ID</label><input ref={transferRef} id="recipient" placeholder="Paste their wallet ID" value={recipient} onChange={e => setRecipient(e.target.value)} disabled={!session || frozen} autoComplete="off"/><div className="amount-label"><label htmlFor="amount">Amount</label><span>Indian rupees</span></div><div className="amount-input"><span>₹</span><input id="amount" inputMode="decimal" placeholder="0.00" value={amount} onChange={e => setAmount(e.target.value)} disabled={!session || frozen}/><span>INR</span></div><button className="button primary full" disabled={!session || frozen}>Review transfer<ArrowRight size={17}/></button><p className="form-footnote"><ShieldCheck size={13}/>You’ll confirm the details before money moves.</p></form></section>
            <section className="panel activity-panel"><div className="section-title spread"><div><h2>Session activity <span className="count">{activity.length}</span></h2><p>Transfers made or looked up in this session.</p></div><span className="subtle">Most recent first</span></div>{activity.length === 0 ? <div className="empty-state"><div className="empty-icon"><ArrowUpRight size={24}/><ArrowDownLeft size={18}/></div><h3>A fresh start.</h3><p>Your transfers will appear here.<br/>Make your first move when you’re ready.</p></div> : <div className="activity-list">{activity.map(t => <button className="activity-row" key={t.id} onClick={() => setDetails(t)}><span className={`activity-icon ${t.reversal_of ? 'refund' : ''}`}>{t.reversal_of ? <RotateCcw size={18}/> : t.from === wallet?.id ? <ArrowUpRight size={19}/> : <ArrowDownLeft size={19}/>}</span><span className="activity-text"><strong>{t.reversal_of ? 'Refund' : t.from === wallet?.id ? 'Money sent' : 'Money received'}</strong><small>{short(t.id)}</small></span><span className="activity-amount"><strong>{formatMoney(t.amount_paise)}</strong><small className={t.status}>{statusLabel(t.status)}</small></span><ChevronRight size={16}/></button>)}</div>}</section>
            <section className="panel lookup-panel"><div className="section-title"><span className="small-icon"><Search size={18}/></span><div><h2>Find or refund</h2><p>Look up a payment, then reverse it as the recipient.</p></div></div><form className="lookup-form" onSubmit={lookup}><label htmlFor="lookup">Transfer ID</label><div><input ref={lookupRef} id="lookup" placeholder="Paste a transfer ID" value={lookupId} onChange={e => setLookupId(e.target.value)} disabled={!session || frozen}/><button className="button secondary" disabled={!session || frozen || lookupBusy}>{lookupBusy ? 'Finding…' : 'Look up'}</button></div></form>{details ? <div className="transfer-detail" aria-label="Transfer details"><div className="detail-heading"><strong>{formatMoney(details.amount_paise)}</strong><span className={`status-badge ${details.status}`}>{statusLabel(details.status)}</span></div><dl><dt>Transfer ID</dt><dd><code>{details.id}</code><CopyId value={details.id} label="Copy transfer ID"/></dd><dt>From</dt><dd><code>{details.from}</code></dd><dt>To</dt><dd><code>{details.to}</code></dd>{details.reversal_of && <><dt>Original transfer</dt><dd><code>{details.reversal_of}</code></dd></>}</dl>{details.reason && <p className="decline-reason">{explain(details.reason)}</p>}{details.status === 'succeeded' && !details.reversal_of && details.to === wallet?.id ? <button className="button secondary full" onClick={() => reviewRefund(details)} disabled={frozen}><RotateCcw size={15}/>Review full refund</button> : <p className="detail-note">{details.reversal_of ? 'This refund is stored as its own transfer.' : details.status === 'declined' ? 'No money moved for this attempt.' : 'Only the original recipient sees the refund action.'}</p>}</div> : <div className="lookup-hint"><CircleHelp size={17}/><p>After Alice sends money, copy the transfer ID, connect as Bob, look it up here, then issue the full refund.</p></div>}</section>
          </div>
          <footer className="page-footer"><span><ShieldCheck size={14}/>Balances are stored in integer paise.</span><a href="/api/healthz" target="_blank" rel="noreferrer">Service health<ExternalLink size={13}/></a></footer>
        </div>
      </main>
    </div>
    {operation && <div className="modal-backdrop"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" ref={modalRef} tabIndex={-1} onKeyDown={modalKeys}>
      {!frozen && <button className="modal-close icon-button" aria-label="Close dialog" onClick={closeModal}><X size={20}/></button>}
      <div className={`modal-icon ${operation.phase === 'done' && operation.result.status === 'declined' ? 'warn' : ''}`}>{operation.phase === 'done' ? operation.result.status === 'succeeded' ? <Check size={26}/> : <X size={26}/> : operation.kind === 'refund' ? <RotateCcw size={24}/> : <ArrowUpRight size={26}/>}</div>
      <h2 id="modal-title">{operation.phase === 'review' ? operation.kind === 'refund' ? 'Review your refund' : 'Ready to send?' : operation.phase === 'done' ? operation.result.status === 'succeeded' ? operation.kind === 'refund' ? 'Refund completed' : 'Money sent' : 'Transfer declined' : operation.phase === 'uncertain' ? 'Let’s check that transfer' : 'Request not completed'}</h2>
      <p className="modal-description">{operation.phase === 'review' ? operation.kind === 'refund' ? 'Return the full original amount to the sender.' : 'Take a moment to check the recipient and amount.' : operation.phase === 'done' ? operation.result.reason ? explain(operation.result.reason) : 'The result is saved. Your balance has been refreshed if available.' : operation.error}</p>
      <div className="review-summary"><strong>{formatMoney(opAmount)}</strong><span>To wallet</span><code>{opTo}</code></div>
      {operation.phase === 'done' && <div className="result-id"><span>Transfer ID</span><code>{operation.result.id}</code><CopyId value={operation.result.id} label="Copy result transfer ID"/></div>}
      {operation.phase === 'uncertain' && <p className="retry-note" role="alert">Keep this window open. Retry uses the same request key and cannot apply the transfer twice. Refreshing the page loses this session’s request.</p>}
      <details className="technical-details"><summary>Request details</summary><p>Idempotency key</p><code>{operation.body.idempotency_key}</code>{operation.correlation && <><p>Correlation ID</p><code>{operation.correlation}</code></>}</details>
      {(operation.phase === 'review' || operation.phase === 'uncertain') ? <button className="button primary full" disabled={busy} onClick={execute}>{busy ? 'Checking…' : operation.phase === 'uncertain' ? 'Retry same request' : operation.kind === 'refund' ? 'Confirm refund' : 'Confirm transfer'}<ArrowRight size={17}/></button> : <button className="button primary full" onClick={() => { setOperation(null); setAmount(''); }}>Done</button>}
      {operation.phase === 'review' && <button className="cancel-button" disabled={busy} onClick={closeModal}>Go back</button>}
    </section></div>}
  </>;
}
