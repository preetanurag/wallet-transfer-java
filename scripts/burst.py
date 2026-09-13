#!/usr/bin/env python3
"""Run against isolated, seeded demo wallets. Uses only the Python standard library."""
import concurrent.futures
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get('BASE_URL', 'http://localhost:8080').rstrip('/')
TOKENS = json.loads(os.environ.get('AUTH_TOKENS', '{"alice":"demo-alice-token-0001","bob":"demo-bob-token-000001","carol":"demo-carol-token-0001","fresh":"demo-fresh-token-0001"}'))
WALLET_RACE_REQUESTS = int(os.environ.get('WALLET_RACE_REQUESTS', '50'))
IDEMPOTENCY_RETRIES = int(os.environ.get('IDEMPOTENCY_RETRIES', '30'))
CONTENDED_TRANSFERS = int(os.environ.get('CONTENDED_TRANSFERS', '120'))
BURST_WORKERS = int(os.environ.get('BURST_WORKERS', '30'))
REQUEST_TIMEOUT_SECONDS = int(os.environ.get('REQUEST_TIMEOUT_SECONDS', '45'))
TRANSIENT_RETRIES = int(os.environ.get('TRANSIENT_RETRIES', '4'))
TRANSIENT_STATUSES = {429, 503}

def request(user, path, body=None):
    last = None
    for attempt in range(TRANSIENT_RETRIES + 1):
        req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization': 'Bearer ' + TOKENS[user], 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                if response.status != 200:
                    raise RuntimeError('Unexpected HTTP status: ' + str(response.status))
                return json.load(response)
        except urllib.error.HTTPError as error:
            body_text = error.read().decode(errors='replace')[:300]
            if error.code not in TRANSIENT_STATUSES or attempt == TRANSIENT_RETRIES:
                raise RuntimeError('Unexpected HTTP status: ' + str(error.code) + ' ' + body_text) from error
            last = error
        except TimeoutError as error:
            if attempt == TRANSIENT_RETRIES:
                raise
            last = error
        time.sleep(0.5 * (attempt + 1))
    raise last

def check(condition, message):
    if not condition:
        raise RuntimeError(message)

def parallel(count, fn, workers=30):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(fn, range(count)))

fresh = parallel(WALLET_RACE_REQUESTS, lambda _: request('fresh', '/wallets', {}), WALLET_RACE_REQUESTS)
check(len({w['id'] for w in fresh}) == 1, 'Wallet creation race')
print(f'PASS: {WALLET_RACE_REQUESTS} concurrent get-or-create requests returned one wallet.')
users = ['alice', 'bob', 'carol']
wallets = [request(user, '/wallets', {}) for user in users]
check(wallets[0]['balance_paise'] >= 100, 'Seed Alice before running the burst')
before = [w['balance_paise'] for w in wallets]
body = {'from': wallets[0]['id'], 'to': wallets[1]['id'], 'amount_paise': 100, 'idempotency_key': str(uuid.uuid4())}
replies = parallel(IDEMPOTENCY_RETRIES, lambda _: request('alice', '/transfers', body))
check(all(r == replies[0] for r in replies), 'Replay responses differ')
check(replies[0]['status'] == 'succeeded', 'Expected successful transfer')
after = [request(user, '/wallets/' + w['id'])['balance_paise'] for user, w in zip(users, wallets)]
check(after == [before[0] - 100, before[1] + 100, before[2]], 'More than one balance effect')
print(f'PASS: {IDEMPOTENCY_RETRIES} identical retries produced exactly one debit and credit.')

def move(i):
    source = i % 3
    dest = (source + (1 if i % 2 else 2)) % 3
    return request(users[source], '/transfers', {'from': wallets[source]['id'], 'to': wallets[dest]['id'],
        'amount_paise': 9007199254740991 if i % 9 == 0 else 17, 'idempotency_key': str(uuid.uuid4())})

results = parallel(CONTENDED_TRANSFERS, move, BURST_WORKERS)
after = [request(user, '/wallets/' + w['id'])['balance_paise'] for user, w in zip(users, wallets)]
check(sum(before) == sum(after), 'Conservation failed')
check(min(after) >= 0, 'Negative balance')
check(any(r['status'] == 'declined' for r in results), 'Expected overdraft declines')
print(f'PASS: {CONTENDED_TRANSFERS} contended transfers conserved money; overdrafts declined; no negative balances.')
