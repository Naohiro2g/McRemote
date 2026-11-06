# 共有fixtureの取得元

下表の7本は、[`Naohiro2g/minecraft-remote-tooling`](https://github.com/Naohiro2g/minecraft-remote-tooling/tree/21a2a9a0aed8b4d9c715da46616aad86f1174b4c/packages/protocol/test/fixtures)の
commit `21a2a9a0aed8b4d9c715da46616aad86f1174b4c`、
`packages/protocol/test/fixtures/`からコピーした未加工のJSONです。
各ファイルのbytesとSHA-256を同commitのraw bytesと照合しています。

| ファイル | bytes | SHA-256 |
| --- | ---: | --- |
| `chat-event-compat-v23.2.1.json` | 32,384 | `eead244a7340c315cb63e1e77ede0fe9cf48162c06fedbeef36d941950d5000d` |
| `chat-event-compat-v23.2.json` | 32,382 | `670b0a86df1956c0e44c6986a0a2598caab32c7328804f9c703190e62e9dd727` |
| `direction-lightning-v23.1.json` | 20,367 | `586d24bf40136eec31f1827f23ef5b317f15100a17a635d7fe9f165e0af40dce` |
| `entity-particle-v23.2.1.json` | 36,483 | `0319ff8373a96798cf5e9e421a804729fd33a0fa53e96adc12c93ed7994777ae` |
| `entity-particle-v23.2.json` | 36,481 | `ca636b4a2685ea67f24d8e7931e3d30a84e7cec872bb5c5d2eadd178cdac39f2` |
| `events-v23.json` | 2,112 | `31760d267f3c2641042fbe8595fda9c259134a1c05423271a99cb74da1efa9aa` |
| `sign-v23.json` | 5,043 | `7ffb63c264602cba56117eefff1f9604b955df04c5cc655e877772b8ff7cd30e` |

この7本に対応するMIT Licenseを、取得元と同じ内容で[`LICENSE`](LICENSE)に保持しています。
1,070 bytes、SHA-256は`aa329a0bafc839b95124306141db13a381de45818bf629e983c44ea55ab5a7fb`です。
MITの適用範囲は取得元のfixtureディレクトリであり、toolingの他の部分はAGPL-3.0-onlyのままです。

同じディレクトリにある`b5-chunk-admission-v23.json`、
`b5-plugin-runtime-policy-v23.json`、`dimension-key-v23.json`はMcRemote独自のfixtureです。
toolingからのコピーには含まず、本リポジトリの[BSD 2-Clause License](../../../../LICENSE)に従います。

`release/contracts/`のSchema・fixtureは別の取得元・契約で管理しており、
このMIT Licenseの対象には含めません。
