const messages = {
  unauthorized: 'That token was not accepted. Check it and connect again.',
  source_wallet_not_owned: 'You can only send money from your own wallet.',
  reversal_requires_recipient: 'Only the original recipient can issue this refund.',
  transfer_already_reversed: 'This transfer has already been refunded.',
  transfer_not_succeeded: 'A declined transfer cannot be refunded.',
  cannot_reverse_reversal: 'A refund cannot itself be reversed.',
  idempotency_key_conflict: 'This request key belongs to a different operation.',
  wallet_not_found: 'Wallet not found or not accessible to this account.',
  transfer_not_found: 'Transfer not found or not accessible to this account.',
  service_unavailable: 'The service is temporarily unavailable. Retry the same request.',
  insufficient_funds: 'There are not enough funds in the sending wallet.',
  recipient_balance_limit: 'The receiving wallet would exceed its balance limit.',
};
export const explain = code => messages[code] || code?.replaceAll('_', ' ') || 'The request could not be completed.';
export async function api(path, token, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30000);
  try {
    const response = await fetch('/api' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const correlation = response.headers.get('x-correlation-id');
    const value = await response.json();
    if (!response.ok) throw Object.assign(new Error(explain(value.error)), { status: response.status, correlation, uncertain: response.status >= 500 });
    return { value, correlation };
  } catch (error) {
    if (error.status) throw error;
    throw Object.assign(new Error('The response was not received. The transfer may have completed; retry this same request to check.'), { uncertain: true });
  } finally { clearTimeout(timeout); }
}
