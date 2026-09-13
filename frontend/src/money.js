export const MAX_PAISE = 9007199254740991n;
export function parseRupees(value) {
  const text = value.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(text)) throw new Error('Enter a positive amount with up to two decimal places.');
  const [whole, fraction = ''] = text.split('.');
  const paise = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (paise < 1n || paise > MAX_PAISE) throw new Error('Amount must be between ₹0.01 and ₹90,071,992,547,409.91.');
  return Number(paise);
}
export function formatMoney(paise) {
  const amount = BigInt(paise);
  return `₹${(amount / 100n).toLocaleString('en-IN')}.${(amount % 100n).toString().padStart(2, '0')}`;
}
export function validId(id) {
  return /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(id);
}
