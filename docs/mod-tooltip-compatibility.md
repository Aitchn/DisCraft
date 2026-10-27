# 模組物品 tooltip：內容相容方案（待決策，未實作）

使用者提供的「贝壳号角」對照中，Discord 只有名稱，而遊戲還有紫色召喚說明、灰色
使用條件、來源標示及圖示／黃色框。這是目前擷取範圍不足，不能用載入語言 ZIP
解釋或宣稱已完整支援。語言包只能替換存在的翻譯 key，不能自行產生模組 callback
才加入的行，也無法重現 client-only tooltip 主題。

建議先做「經審核的服務端內容 adapter」，保留現在的普通 tooltip 當安全基底：

1. 先取得物品 registry ID（可開 F3+H 查看）與所屬模組確切版本，核對官方 source
   或已合法安裝的該版本實作；不需安裝整套模組包或傳送存檔／私人 NBT。
2. 逐物品／模組白名單擷取必要的服務端權威語意；adapter 自己做有界、可測的
   純資料轉換，輸出有樣式的 literal／translatable Component。共用現有語系索引、
   逐 key 資源包覆蓋、翻譯展開／字數／行數／深度上限及 tooltip renderer。
3. 只允許明訂欄位；不將完整 ItemStack tag、容器、書頁、命令、click action、
   任意 URL 或本機路徑交給 Discord。來源、條件與數值以服務端 snapshot 為準。
4. callback 若引用 client class、玩家 client 狀態或不明副作用，直接列為不支援。
   不通用呼叫所有模組的 `appendHoverText`／tooltip event，也不宣稱 Future timeout
   能硬性中止遊戲執行緒內的任意模組程式。未審核版本保留有限 fallback，清楚說明缺項。
5. 可用同版本 fixture／本機正式 adapter 驗證新增語意行與原生對照，另測翻譯覆蓋、
   缺 key、長文、敏感欄位、版本不符和 dedicated server 無 DISPLAY。未經另行授權
   不發送或編輯真實 Discord 訊息。

圖示和框線另外處理：只有 adapter 明確提供的安全資源 ID 才能從既有本地資源索引取圖；
不執行模型 loader／client UI callback。黃色邊框、來源列圖示等 client theme 不能只由
rarity 或漢化包推定。可以之後明確選擇有限的服務端樣式設定，但不保證像素級還原。

Optional client 不能取代權威來源。直接採信客戶端提供的 tooltip 行／截圖會允許偽造
物品展示，因此不建議把它當作自動補全方案；目前也沒有實作這條路徑。

最小下一步決策：提供該物品 registry ID 和模組版本後，先評估服務端內容 adapter；
圖示／框線等裝飾留作獨立需求。若該版本只有 client-only 內容，再回報具體缺口與取捨，
不把只有名稱的輸出標為完整相容。
