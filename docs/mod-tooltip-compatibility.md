# 模組物品 tooltip：通用相容

舊版只用伺服器白名單重建 tooltip（名稱、附魔、NBT Lore、屬性、不可破壞、耐久度），
所以像 CTE2 的「贝壳号角」在 Discord 只剩名稱：模組說明由物品自己的 `appendHoverText`
產生，模組名稱行與 Mine and Slash 數值則是客戶端 tooltip 事件加上的，伺服器端看不到。

現在改成「白名單為底線，較完整的來源若能延伸底線就採用」，不逐模組寫死：

1. **客戶端回報（`itemTooltipClient`，預設 `strict`）**：送出含 `%item`／`%i` 的訊息時，
   裝有 DisCraft 的客戶端以 `getTooltipLines(player, NORMAL)` 取得自己實際顯示的行
   （含模組與客戶端 `ItemTooltipEvent`），以 Component JSON 透過獨立的 `discraft:tooltip`
   頻道（版本 1，舊客戶端／舊伺服器自動略過）在草稿上傳前送出。
2. **伺服器原生（`itemTooltipNative`，預設 `true`）**：在遊戲執行緒對已去除私密 NBT 的
   複本呼叫原生 `getTooltipLines`。丟例外或超過 50 ms 的物品 ID 在本次執行期間停用並記錄一次。
3. **白名單**：以上皆不合格時使用，行為與舊版相同。

## 驗證

- 物品 ID 與數量必須與伺服器擷取一致；`strict` 另外要求名稱一致。
- `strict`：伺服器白名單的每一行都必須依序原樣出現，來源只能「加行」，不能改或刪。
  耐久度是只在進階 tooltip 才有的行，單獨比對並一律以伺服器數值附加。
- `lenient`：只要求 ID、數量一致，整份採用客戶端顯示的 tooltip。給會改名或改寫原版行的模組使用，
  例如 Mine and Slash 裝備（標題變成「加固的 皮甲胸甲 之蛇」、原版護甲行被換成 M&S 數值）。
  `off` 不用客戶端資料。
- 客戶端 JSON 先檢查巢狀深度，只保留文字與樣式；點擊／懸停事件、NBT／選擇器／分數等
  會讀取伺服器資料的元件一律不解析（顯示為 unsupported）。行數、字數、封包大小有上限。
- 文字中的舊式 `§` 格式碼會像客戶端字型渲染一樣套用顏色與樣式，而不是原樣印出。
- 較完整的 tooltip 若超過圖片預算，退回白名單版本，不會讓訊息送出失敗。

## 已知限制與風險

- 改過的客戶端可以加入假的說明行（`strict` 下不能改動伺服器算出的行）。這與已授權玩家本來
  就能上傳任意截圖是同等級；不接受時設 `itemTooltipClient=off`。
- 只在 `RenderTooltipEvent.GatherComponents`（而非 `ItemTooltipEvent`）加入的內容、圖片型
  tooltip 元件、以及自訂框線主題不會出現；Discord 圖片維持 DisCraft 的 Minecraft 風格。
- ✔ ✦ ☠ ❤ 等符號：遊戲用 Unifont 繪製，伺服器沒有客戶端資源，所以內建 Unifont 的
  U+2070–U+2E7F 符號子集（`symbols.ttf`，OFL）作為中文字型之後的備援。
- 其他自訂字型字元需要伺服器端能載入該字型（已安裝模組的 `font/` 資源或設定的資源包），
  缺字時圖片底部會列出缺少的字元。
- 遊戲內聊天懸停只帶白名單 NBT（`NativeItemData`），模組自己的 NBT（例如 M&S 的「靈魂」資料）
  不會傳給其他玩家，所以他們的懸停會顯示「此装备没有灵魂」。
- 原生擷取無法硬性中止卡住的模組程式；只能事後停用該物品。
