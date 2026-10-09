# 同梱ライブラリ

McRemoteのプラグインコードはBSD-2-Clauseです。

- Xerial SQLite JDBC **3.53.4.0**（SQLite **3.53.4**）をnative libraryとともに同梱します。起動時のdownloadは不要です。
- Xerial SQLite JDBCはApache License 2.0です。元実装のDavid CrawshawのBSDライセンスと上流NOTICEも保持しています。全文は`META-INF/licenses/sqlite-jdbc/`にあります。Java/nativeのコードを改変せず、配布JARの形にまとめています。
- SQLite本体はpublic domainです。著作権の説明: https://www.sqlite.org/copyright.html
- Xerialの配布元: https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0
- WAL-resetの修正を含むSQLiteの版: https://www.sqlite.org/wal.html#the_wal_reset_bug
