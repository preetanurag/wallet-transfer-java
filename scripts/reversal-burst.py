#!/usr/bin/env python3
"""Exercise full refunds against isolated demo wallets; only Python stdlib required.
BASE_URLS may list comma-separated app instances sharing one database.
"""
import concurrent.futures
import itertools
import json
import os
import urllib.error
import urllib.request
import uuid

URLS = [url.rstrip('/') for url in os.environ.get('BASE_URLS', os.environ.get('BASE_URL', 'http://localhost:8080')).split(',')]
NEXT_URL = itertools.cycle(URLS)
TOKENS = json.loads(os.environ.get('AUTH_TOKENS', '{"alice":"demo-alice-token-0001","bob":"demo-bob-token-000001","carol":"demo-carol-token-0001"}'))

def request(user, path, body=None, expected=200):
    req = urllib.request.Request(next(NEXT_URL) + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + TOKENS[user], 'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        check(response.code == expected, 'Unexpected response: ' + str(response.code) + ' ' + str(value))
        return value

def check(condition, message):
    if not condition:
        raise RuntimeError(message)

def key():
    return str(uuid.uuid4())

def parallel(count, fn):
    with concurrent.futures.ThreadPoolExecutor(max_workers=30) as pool:
        return list(pool.map(fn, range(count)))

users = ['alice', 'bob', 'carol']
wallets = [request(user, '/wallets', {}) for user in users]

def balances():
    return [request(user, '/wallets/' + w['id'])['balance_paise'] for user, w in zip(users, wallets)]

def move(source, target, amount):
    result = request(users[source], '/transfers', {'from': wallets[source]['id'], 'to': wallets[target]['id'],
        'amount_paise': amount, 'idempotency_key': key()})
    check(result['status'] == 'succeeded', 'Expected funding/spending transfer to succeed: ' + str(result))
    return result

def reverse(original, token, expected=200):
    return request('bob', '/transfers/' + original['id'] + '/reverse', {'idempotency_key': token}, expected)

before = balances()
check(before[0] >= 100, 'Seed demo balances first')
original = move(0, 1, 100)
refund_key = key()
replies = parallel(30, lambda _: reverse(original, refund_key))
check(all(r == replies[0] for r in replies), 'Same-key reversal results differ')
check(replies[0]['status'] == 'succeeded', 'Refund should succeed')
check(replies[0]['reversal_of'] == original['id'], 'Missing original link')
check(balances() == before, 'Refund did not restore balances exactly')
check(reverse(original, key(), 409)['error'] == 'transfer_already_reversed', 'Second refund not rejected')
check(reverse(original, refund_key) == replies[0], 'Stored refund replay changed')
print('PASS: 30 concurrent reversal retries refunded once; new-key double refund rejected.')

original = move(0, 1, 100)
# Concurrent distinct keys must yield exactly one 200 and one 409.
def compete(_):
    req = urllib.request.Request(next(NEXT_URL) + '/transfers/' + original['id'] + '/reverse',
        data=json.dumps({'idempotency_key': key()}).encode(),
        headers={'Authorization': 'Bearer ' + TOKENS['bob'], 'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, json.load(response)
results = parallel(2, compete)
check(sorted(status for status, _ in results) == [200, 409], 'Distinct-key race failed: ' + str(results))
check(balances() == before, 'Distinct keys caused an extra money movement')
print('PASS: competing reversal keys produced one refund.')

original = move(0, 1, 100)
drained = balances()[1]
move(1, 2, drained)
declined_key = key()
declined = reverse(original, declined_key)
check(declined['status'] == 'declined' and declined['reason'] == 'insufficient_funds', 'Expected insufficient-funds decline')
check(balances()[1] == 0 and sum(balances()) == sum(before), 'Decline changed total or overdrew Bob')
move(2, 1, drained)
check(reverse(original, declined_key) == declined, 'Declined replay changed after funding')
check(reverse(original, key())['status'] == 'succeeded', 'New attempt after funding should succeed')
check(balances() == before, 'Final balances not restored')
print('PASS: spent funds cause a persistent decline; a fresh key after funding succeeds.')
