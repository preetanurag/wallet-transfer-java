import { describe, it, expect } from 'vitest';
import { parseRupees, formatMoney } from '../src/money.js';
describe('exact paise amounts', () => {
  it.each([['0.01',1],['1.10',110],['1000',100000],['90071992547409.91',9007199254740991]])('converts %s without floating point rounding', (input, expected) => expect(parseRupees(input)).toBe(expected));
  it.each(['0','-1','1.001','1e3','Infinity','NaN','90071992547409.92','1,000',''])('rejects %s', input => expect(() => parseRupees(input)).toThrow());
  it('formats the upper boundary exactly', () => expect(formatMoney(9007199254740991)).toBe('₹9,00,71,99,25,47,409.91'));
});
