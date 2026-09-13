# Postman

Import `Wallet.postman_collection.json` and `Local.postman_environment.json`. Select **Wallet Local Docker** as the active environment. Docker API base URL is `http://localhost:8180`; change it to `http://localhost:8080` for IntelliJ.

Run the whole collection in folder order. Setup captures wallet IDs. The first transfer creates fresh keys and captures its ID; replay requests retain those keys. Bob authorizes the refund. A full successful workflow restores the 100 paise it transfers. Individual sends can change balances until you execute the refund.

Prerequisite: seed demo wallets before their initial API creation. Existing zero-balance wallets are not topped up by the seed script. Alice needs at least 100 paise. Use only isolated demo wallets. This collection tests sequential API behavior; use the Python burst scripts for concurrency, insufficient-funds refund scenarios, and the integration suite for injected rollback failures.

No production secrets are included. For a deployed environment, duplicate the local environment and replace the base URL and tokens locally. Do not publish populated secret exports.
