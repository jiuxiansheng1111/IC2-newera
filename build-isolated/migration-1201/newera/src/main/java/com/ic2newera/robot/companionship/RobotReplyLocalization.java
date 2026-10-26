package com.ic2newera.robot.companionship;

import java.util.HashMap;
import java.util.Map;

/** Localizes fixed server-authored robot replies without translating player data or registry IDs. */
public final class RobotReplyLocalization {
    private record Text(String japanese, String english) { }
    private static final Map<String, Text> FIXED = new HashMap<>();

    static {
        add("机器人未加载、离线或权限已改变，未执行命令。", "ロボットが読み込まれていないか、オフライン、または権限が変更されています。命令は実行されませんでした。", "The robot is unloaded, offline, or your permissions changed. The command was not executed.");
        add("机器人未加载、离线或权限已改变，未派发任务。", "ロボットが読み込まれていないか、オフライン、または権限が変更されています。タスクは割り当てられませんでした。", "The robot is unloaded, offline, or your permissions changed. No task was assigned.");
        add("机器人未加载、离线或权限已改变，未派发烹饪任务。", "ロボットが読み込まれていないか、オフライン、または権限が変更されています。調理タスクは割り当てられませんでした。", "The robot is unloaded, offline, or your permissions changed. No cooking task was assigned.");
        add("机器人未加载、离线或权限不符；近身派任务需在八格内。", "ロボットが読み込まれていないか、オフライン、または権限がありません。近距離からの依頼には8ブロック以内にいる必要があります。", "The robot is unloaded, offline, or you lack permission. Stand within eight blocks to assign a nearby task.");
        add("角色离线、超出操作距离或不属于你；远程操作需要手持手机。", "ロボットがオフライン、操作範囲外、またはあなたの所有物ではありません。遠隔操作には携帯電話を手に持つ必要があります。", "The robot is offline, out of range, or not yours. Remote control requires holding the phone.");
        add("只有机器人的所有者可以派发采集任务。", "採集タスクを割り当てられるのはロボットの所有者だけです。", "Only the robot's owner can assign collection tasks.");
        add("没有可执行的白名单工作命令。", "許可リストにある実行可能な作業命令がありません。", "There is no executable work command on the allowlist.");
        add("操作过快，请稍后重试。", "操作が速すぎます。しばらくしてからもう一度お試しください。", "That was too fast. Please try again shortly.");
        add("手机电量不足，请携带有电的充电宝或使用 IC2 充电设备。", "携帯電話の電力が不足しています。充電済みの携帯用バッテリーを持つか、IC2の充電設備を使用してください。", "The phone is low on power. Bring a charged portable battery or use an IC2 charging device.");
        add("机器人需要至少200 EU才能接收工作命令。", "作業命令を受け付けるにはロボットに200 EU以上必要です。", "The robot needs at least 200 EU to accept a work command.");
        add("机器人需要至少200 EU才能接收采集任务。", "採集タスクを受け付けるにはロボットに200 EU以上必要です。", "The robot needs at least 200 EU to accept a collection task.");
        add("机器人至少需要200 EU才能接收烹饪任务。", "調理タスクを受け付けるにはロボットに200 EU以上必要です。", "The robot needs at least 200 EU to accept a cooking task.");
        add("玩家状态不可用。", "プレイヤーの状態を確認できません。", "The player state is unavailable.");
        add("请求过于频繁或仍在处理中，请5秒后重试。", "リクエストが多すぎるか、まだ処理中です。5秒後にもう一度お試しください。", "Too many requests, or one is still processing. Try again in 5 seconds.");
        add("需要持有绑定给自己的虫洞手机。", "自分に紐づけられたワームホール携帯電話を手に持つ必要があります。", "You must hold the wormhole phone bound to you.");
        add("请求上下文已结束或权限已变化。", "リクエストの処理が終了したか、権限が変更されました。", "The request context ended or your permissions changed.");
        add("手机未绑定可加载的供能设备。", "携帯電話に読み込み可能な電源装置が紐づけられていません。", "The phone is not bound to a loadable power device.");
        add("尚未绑定此信标目的地。", "このビーコンの目的地はまだ登録されていません。", "This beacon destination has not been bound.");
        add("机器人最近位置不可达；未加载或生成新区域。", "ロボットの最近位置に到達できません。新しいエリアの読み込みや生成は行っていません。", "The robot's recent position is unreachable. No new area was loaded or generated.");
        add("无效或不可达的传送目的地。", "転送先が無効か、到達できません。", "The teleport destination is invalid or unreachable.");
        add("目标区块无法从现有世界安全加载。", "既存ワールドから目的のチャンクを安全に読み込めません。", "The target chunk cannot be safely loaded from the existing world.");
        add("请求上下文已结束或权限已变化；未传送。", "リクエストの処理が終了したか、権限が変更されたため、転送しませんでした。", "The request context ended or your permissions changed. No teleport occurred.");
        add("目标区块未能从已有存档安全加载；未生成新区域。", "既存のワールドデータから目的のチャンクを安全に読み込めませんでした。新しいエリアは生成していません。", "The target chunk could not be safely loaded from existing world data. No new area was generated.");
        add("虫洞传送预检异常，未执行移动。", "ワームホール転送の事前確認でエラーが発生しました。移動は実行されていません。", "Wormhole teleport preflight failed. No movement was performed.");
        add("虫洞预检失败，未执行移动。", "ワームホールの事前確認に失敗しました。移動は実行されていません。", "Wormhole preflight failed. No movement was performed.");
        add("玩家状态不允许传送。", "プレイヤーの状態では転送できません。", "The player's current state does not allow teleporting.");
        add("玩家正处于乘骑、载客或睡眠状态，不能传送。", "プレイヤーが騎乗中、乗り物として搭乗者を乗せている、または睡眠中のため転送できません。", "Teleporting is unavailable while the player is riding, carrying passengers, or sleeping.");
        add("无效传送类型。", "転送の種類が無効です。", "The teleport action is invalid.");
        add("虫洞冷却中，请稍后再试。", "ワームホールはクールダウン中です。しばらくしてから再度お試しください。", "The wormhole is cooling down. Try again shortly.");
        add("需要持有已绑定给自己的手机与虫洞模块。", "自分に紐づけられた携帯電話とワームホールモジュールを手に持つ必要があります。", "You must hold a phone bound to you with a wormhole module installed.");
        add("手机没有绑定可用的自有虫洞供能设备。", "携帯電話に使用可能な自分所有のワームホール電源装置が紐づけられていません。", "The phone is not bound to an available wormhole power device you own.");
        add("IC2R 电量服务不可用。", "IC2Rの電力サービスを利用できません。", "The IC2R energy service is unavailable.");
        add("信标目的地区块未加载；为避免强制生成区块，本次已拒绝。", "ビーコンの目的地チャンクが読み込まれていません。チャンクを強制生成しないため、今回は拒否しました。", "The beacon destination chunk is not loaded. This request was denied to avoid forcing chunk generation.");
        add("绑定的信标已不存在。", "登録済みのビーコンが見つかりません。", "The bound beacon no longer exists.");
        add("信标附近没有安全落点。", "ビーコンの近くに安全な着地点がありません。", "There is no safe landing near the beacon.");
        add("请选择已绑定的机器人。", "登録済みのロボットを選択してください。", "Select a bound robot.");
        add("机器人离线或不属于你。", "ロボットがオフラインか、あなたの所有物ではありません。", "The robot is offline or does not belong to you.");
        add("机器人尚未安装虫洞模块。", "ロボットにワームホールモジュールが取り付けられていません。", "The robot does not have a wormhole module installed.");
        add("机器人正在工作、战斗、搭乘或牵绳状态，不能传送。", "ロボットが作業中、戦闘中、騎乗中、搭乗者を乗せている、またはリードにつながれているため転送できません。", "The robot cannot teleport while working, fighting, riding, carrying passengers, or leashed.");
        add("目标附近没有安全落点，或目标区块尚未加载。", "目的地の近くに安全な着地点がないか、目的のチャンクがまだ読み込まれていません。", "There is no safe landing near the target, or the target chunk is not loaded.");
        add("手机电量预留失败，未传送。", "携帯電話の電力を確保できなかったため、転送しませんでした。", "Could not reserve phone energy. No teleport occurred.");
        add("机器人电池预留失败，未传送。", "ロボットのバッテリー電力を確保できなかったため、転送しませんでした。", "Could not reserve robot battery energy. No teleport occurred.");
        add("供能设备预留失败，未传送。", "電源装置のエネルギーを確保できなかったため、転送しませんでした。", "Could not reserve power-device energy. No teleport occurred.");
        add("维度切换被取消或传送未完成；已回滚预留电量。", "ディメンション移動がキャンセルされたか、転送が完了しませんでした。確保した電力はすべて元に戻しました。", "The dimension change was canceled or teleporting did not complete. All reserved energy was restored.");
        add("已传送至信标。", "ビーコンへ転送しました。", "Teleported to the beacon.");
        add("已传送至机器人身边。", "ロボットのそばへ転送しました。", "Teleported to the robot.");
        add("机器人已传送到你身边。", "ロボットをあなたのそばへ転送しました。", "The robot was teleported to you.");
        add("无效传送目标。", "転送先が無効です。", "The teleport target is invalid.");
        add("手机没有绑定有效的供能设备位置。", "携帯電話に有効な電源装置の位置が登録されていません。", "The phone has no valid power-device location bound to it.");
        add("机器人没有可用的自有联系位置。", "ロボットの利用可能な連絡先位置がありません。", "The robot has no usable saved contact position.");
        add("传送区域请求超过11个区块中心或位置无效。", "転送範囲のチャンク中心が11か所を超えたか、位置が無効です。", "The teleport area exceeds 11 chunk centers, or a position is invalid.");
        add("请求已取消或玩家连接已结束；虫洞票据已释放。", "リクエストがキャンセルされたか、プレイヤーの接続が終了しました。ワームホールの一時チケットは解放されました。", "The request was canceled or the player disconnected. Wormhole tickets have been released.");
        add("已等待10秒仍未完成区块/机器人载入；本次结束且票据已释放。", "10秒待ってもチャンクまたはロボットの読み込みが完了しなかったため、処理を終了し、一時チケットを解放しました。", "The chunk or robot did not finish loading within 10 seconds. The request ended and its tickets were released.");
        add("部分目标区块无法载入；本次结束且票据已释放。", "一部の目的チャンクを読み込めなかったため、処理を終了し、一時チケットを解放しました。", "Some target chunks could not be loaded. The request ended and its tickets were released.");
        add("目标区块载入失败；本次结束且票据已释放。", "目的チャンクの読み込みに失敗したため、処理を終了し、一時チケットを解放しました。", "A target chunk failed to load. The request ended and its tickets were released.");
        add("等待期间持有手机或绑定目的地发生变化；未传送。", "待機中に所持している携帯電話または登録先が変更されたため、転送しませんでした。", "The held phone or its bound destinations changed while waiting. No teleport occurred.");
        add("传送执行异常；请检查实体、手机与设备状态。", "転送処理でエラーが発生しました。エンティティ、携帯電話、装置の状態を確認してください。", "Teleport execution failed. Check the entity, phone, and device state.");
        add("信标目的地区块未完成加载，本次未传送。", "ビーコンの目的チャンクの読み込みが完了していないため、転送しませんでした。", "The beacon destination chunk has not finished loading. No teleport occurred.");
        add("能量预留发生异常，已回滚；未传送。", "エネルギー確保中にエラーが発生したため、すべてロールバックしました。転送は行われていません。", "Energy reservation failed unexpectedly and was rolled back. No teleport occurred.");
        add("玩家已离线；虫洞请求取消并释放临时票据。", "プレイヤーがオフラインになったため、ワームホールのリクエストをキャンセルし、一時チケットを解放しました。", "The player went offline. The wormhole request was canceled and temporary tickets were released.");
        add("机器人需要至少200 EU才能接收任务。", "タスクを受け付けるにはロボットに200 EU以上必要です。", "The robot needs at least 200 EU to accept a task.");
        add("机器人当前电量不足200 EU，未执行命令。", "ロボットの電力が200 EU未満のため、命令は実行されませんでした。", "The robot has less than 200 EU. The command was not executed.");
        add("无电池或电量不足", "バッテリーがないか、電力が不足しています", "No battery or insufficient power");
        add("采集数量必须为 1～256。", "採集数は1～256で指定してください。", "Collection amount must be between 1 and 256.");
        add("采集模式无效（0 按掉落物自动采集，1 仅拾取）。", "採集モードが無効です（0はドロップを自動収集、1は拾得のみ）。", "Invalid collection mode (0: automatically collect drops; 1: pickup only).");
        add("目标物品 ID 无效。", "対象アイテムのIDが無効です。", "The target item ID is invalid.");
        add("不能把空气设为采集目标。", "空気は採集対象に指定できません。", "Air cannot be set as a collection target.");
        add("当前没有指定采集任务，模式与电量不变。", "指定された採集タスクはありません。モードと電力は変更されていません。", "There is no assigned collection task. Mode and energy are unchanged.");
        add("未知建造操作。", "不明な建築操作です。", "Unknown construction action.");
        add("工业任务恢复记录损坏，已锁定施工与生产恢复；原记录和实物保留，请先核对修复，不能用取消任务跳过。", "産業タスクの復旧記録が破損しています。建築と生産の再開をロックしました。記録とアイテムは保持されています。確認・修復が必要で、タスクのキャンセルで回避することはできません。", "The industrial task recovery record is damaged. Construction and production recovery are locked. Records and items are preserved; inspect and repair them. Canceling the task cannot bypass this check.");
        add("请描述房屋或生产线，例如：建一间9x7的木屋，或搭建一条锡生产线。", "家または生産ラインの内容を指定してください。例：9x7の木造住宅を建てて、またはスズ生産ラインを作って。", "Describe a house or production line, for example: build a 9x7 wooden house or a tin production line.");
        add("改造记录属于原主人和原维度；请回到原场站，不会在新位置接管另一条生产线。", "改造記録は元の所有者とディメンションに紐づいています。元の現場に戻ってください。別の場所の生産ラインを引き継ぐことはありません。", "The refit record belongs to the original owner and dimension. Return to the original site; another production line will not be taken over.");
        add("当前角色没有已完成的生产线可换线。", "現在のロボットには、ケーブル交換が可能な完成済み生産ラインがありません。", "This robot has no completed production line available for cable replacement.");
        add("请在换线描述中明确指定一种电缆型号。", "ケーブル交換の指示で、ケーブルの種類を明確に指定してください。", "Specify a cable type in the cable-replacement request.");
        add("改产需要一条已完成的版本2生产线；旧布局请先另选空地生成新版方案。", "生産変更には完成済みのバージョン2生産ラインが必要です。旧レイアウトの場合は、別の空き地で新しい計画を作成してください。", "Production refitting requires a completed version 2 line. For an older layout, create the new plan on a separate empty site.");
        add("方案已过期或角色已改变；请重新生成方案后再确认。", "計画の有効期限が切れたか、ロボットが変更されました。計画を作り直してから確認してください。", "The plan expired or the robot changed. Generate the plan again before confirming.");
        add("已有未完成改造记录；先继续该记录，不能覆盖恢复状态。", "未完了の改造記録があります。復旧状態を上書きせず、まずその記録を続行してください。", "An unfinished refit record already exists. Continue it first; its recovery state cannot be overwritten.");
        add("当前生产线已变化，请重新预览改造方案。", "生産ラインの状態が変わりました。改造計画をもう一度プレビューしてください。", "The production line changed. Preview the refit plan again.");
        add("角色需处于生产线或待命状态才能换线。", "ケーブル交換にはロボットが生産ラインまたは待機モードである必要があります。", "The robot must be in production-line or standby mode to replace cables.");
        add("角色离生产线太远；请先靠近场地再换线。", "ロボットが生産ラインから離れすぎています。現場に近づけてから交換してください。", "The robot is too far from the production line. Move it closer before refitting.");
        add("加工进度或已备物料记录与角色货物槽不一致；请先核对后再改产。", "加工進捗または準備済み材料の記録がロボットの荷物スロットと一致しません。生産を変更する前に確認してください。", "Processing progress or prepared-material records do not match the robot's cargo slots. Reconcile them before refitting.");
        add("生产线已备物料与角色货物槽不一致；请先移开被修改的物品，避免破坏恢复记录。", "生産ラインの準備済み材料がロボットの荷物スロットと一致しません。復旧記録を損なわないよう、変更されたアイテムを先に取り除いてください。", "Prepared production materials do not match the robot's cargo slots. Remove the changed items before proceeding to protect the recovery record.");
        add("启动换线需要200 EU，请补电。", "ケーブル交換の開始には200 EU必要です。充電してください。", "Cable replacement needs 200 EU to start. Recharge the robot.");
        add("存在未完成的生产线改造恢复记录；请先继续该记录。", "未完了の生産ライン改修記録があります。まずその記録を続行してください。", "An unfinished production-line refit record exists. Continue it first.");
        add("已有建造或生产线记录。可继续原任务，或明确取消后再开始新方案；已放方块不会自动拆除。", "建築または生産ラインの記録がすでにあります。既存タスクを続行するか、明示的にキャンセルしてから新しい計画を始めてください。設置済みブロックは自動撤去されません。", "A construction or production-line record already exists. Continue it, or explicitly cancel it before starting a new plan. Placed blocks will not be removed automatically.");
        add("角色已远离预览场地，请重新生成方案。", "ロボットがプレビューした現場から離れました。計画を作り直してください。", "The robot moved away from the preview site. Generate the plan again.");
        add("启动施工需要200 EU，请补电。", "建築の開始には200 EU必要です。充電してください。", "Construction needs 200 EU to start. Recharge the robot.");
        add("没有建造或生产线任务。", "建築または生産ラインのタスクがありません。", "There is no construction or production-line task.");
        add("只有正在运行的新版生产线可以登记周期结束后暂停。", "稼働中の新しい生産ラインのみ、現在の周期終了後に停止する設定ができます。", "Only a running new-version production line can be set to pause after its current cycle.");
        add("没有属于你且在本维度的可恢复任务。", "このディメンションにあなたの再開可能なタスクはありません。", "There is no resumable task owned by you in this dimension.");
        add("角色缺电，请先补电。", "ロボットの電力がありません。先に充電してください。", "The robot is out of power. Recharge it first.");
        add("这座房屋已完成，无需重复施工。", "この家はすでに完成しているため、再度建築する必要はありません。", "This house is already complete; no further construction is needed.");
        add("已取消任务与待确认方案。已放建筑、机器和炉内物品保留，未消耗材料仍在角色货物槽。", "タスクと確認待ちの計画をキャンセルしました。設置済みの建物・機械・炉内アイテムは保持され、未使用の材料はロボットの荷物スロットに残っています。", "Task and pending plan canceled. Placed structures, machines, and furnace contents remain; unused materials stay in the robot's cargo slots.");
        add("改造已停在待命，部分拆装的恢复记录保留；请继续改造或修复现场，生产不会自动恢复。", "改造を待機状態で停止しました。一部の取り外し・設置に関する復旧記録は保持されています。改造を続けるか現場を修復してください。生産は自動再開されません。", "Refitting stopped in standby with partial removal/installation recovery records preserved. Continue refitting or repair the site; production will not resume automatically.");
        add("尚无建造任务。描述例如：建一间9x7的木屋，有玻璃窗；或搭建锡生产线，用低压太阳能供电。电源可指定火力发电机或低压太阳能，未指定时默认火力。先生成方案，再开始施工。", "建築タスクはありません。例：ガラス窓付きの9x7の木造住宅、または低圧ソーラー発電のスズ生産ラインを作って。電源は火力発電または低圧ソーラーを指定できます。未指定時は火力発電です。まず計画を作成し、その後に建築を開始してください。", "There is no construction task yet. For example: build a 9x7 wooden house with glass windows, or a tin production line powered by low-voltage solar. You can specify coal or low-voltage solar power; coal is the default. Generate a plan before starting construction.");
        add("已取消采集任务并待命。", "採集タスクを取り消し、待機状態になりました。", "Collection task canceled. The robot is standing by.");
        add("当前没有烹饪订单或原锅批次。", "調理注文も鍋に残っているバッチもありません。", "There is no cooking order or active pot batch.");
        add("烹饪已安全取消；待投原料退回，原锅物料保留。", "調理を安全にキャンセルしました。投入前の材料は返却され、鍋の中身はそのまま残っています。", "Cooking was safely canceled. Unused ingredients were returned; items in the pot were left untouched.");
        add("原锅或待投原料无法安全核对，任务与实物保留，请检查状态。", "鍋または投入待ちの材料を安全に確認できません。タスクとアイテムは保持されています。状態を確認してください。", "The pot or pending ingredients could not be verified safely. The task and items were preserved; please check the status.");
        add("原锅或待投原料暂时无法安全核对，请检查机器人工作状态；未额外扣除任务费。", "鍋または投入待ちの材料を安全に確認できません。ロボットの作業状態を確認してください。追加の依頼料金は差し引かれていません。", "The pot or pending ingredients could not be verified safely. Check the robot's work status. No additional task fee was charged.");
        add("已取消烹饪绑定并待命；未投入原料已退回，锅内材料和菜品留在原锅。未额外扣除任务费。", "調理の割り当てを解除して待機状態になりました。未投入の材料は返却され、鍋の材料と料理は鍋に残っています。追加料金は差し引かれていません。", "Cooking assignment canceled; the robot is standing by. Unused ingredients were returned, and items in the pot remain there. No additional fee was charged.");
        add("已有烹饪订单或原锅批次；请先完成或安全取消，再派新单。", "調理注文または鍋のバッチが残っています。完了させるか安全にキャンセルしてから、新しい注文を出してください。", "A cooking order or pot batch is already active. Finish or safely cancel it before assigning another.");
        add("请选择有效菜品，数量须为1～256。", "有効な料理を選び、数量を1～256で指定してください。", "Choose a valid food item and an amount from 1 to 256.");
        add("当前已兼容的料理锅没有该物品的真实配方；不会自动改做其他食物。", "対応済みの調理鍋にこのアイテムのレシピがありません。別の料理に自動変更することはありません。", "No real recipe for this item is available in a supported cooking pot. The robot will not substitute another food.");
        add("烹饪任务状态已变化，未重复派单。", "調理タスクの状態が変わったため、重複して割り当てませんでした。", "The cooking task state changed. No duplicate task was assigned.");
        add("本轮不是明确的采集指令，未派发任务。", "今回の発言は明確な採集指示ではないため、タスクは割り当てられませんでした。", "This turn was not a clear collection instruction. No task was assigned.");
        add("请明确要采集的物品和数量，例如：帮我采集20个钨矿。", "採集するアイテムと数量を指定してください。例：タングステン鉱石を20個集めて。", "Specify an item and amount, for example: collect 20 tungsten ore.");
        add("物品名需要在你的本轮指令中明确出现，请补充准确物品名或注册ID。", "アイテム名は今回の指示に明記してください。正確な名前または登録IDを指定してください。", "The item name must appear in your current instruction. Provide its exact name or registry ID.");
        add("一次采集数量为1～256，请给出这个范围内的整数。", "1回の採集数は1～256です。この範囲の整数を指定してください。", "Collection amount must be a whole number from 1 to 256.");
        add("数量还不明确，请一次只指定一个物品和数量，例如：帮我采集20个钨矿。", "数量が明確ではありません。アイテム1種類と数量を指定してください。例：タングステン鉱石を20個集めて。", "The amount is unclear. Specify one item and amount, for example: collect 20 tungsten ore.");
        add("没有识别到明确的物品和整数数量，未派发任务。可说：帮我采集20个钨矿。", "アイテムと整数の数量を確認できないため、タスクは割り当てられませんでした。例：タングステン鉱石を20個集めて。", "No clear item and whole-number amount were detected. No task was assigned. Try: collect 20 tungsten ore.");
        add("请提供明确的物品名称或注册ID。", "アイテムの正確な名前または登録IDを指定してください。", "Provide an exact item name or registry ID.");
        add("没有找到名称完全匹配的已注册物品；请提供更具体的中文名或注册ID。", "名前が完全一致する登録済みアイテムが見つかりません。より正確な名前または登録IDを指定してください。", "No registered item exactly matches that name. Provide a more specific name or registry ID.");
        add("该物品当前未注册或不可用，请提供其他名称或注册ID。", "そのアイテムは現在登録されていないか、利用できません。別の名前または登録IDを指定してください。", "That item is not registered or available. Provide another name or registry ID.");
        add("已切换到采矿。机器人只在附近已加载区域寻找矿石，会自动选用背包工具并拾取真实掉落物；工具、目标或背包阻塞状态会显示并通知。", "採掘モードに切り替えました。読み込み済みの近隣エリアだけを探索し、持ち物から道具を選んで実際のドロップを拾います。道具・対象・インベントリによる停止は表示して通知します。", "Mining mode enabled. The robot searches loaded nearby areas, selects a carried tool, and collects actual drops. Tool, target, or inventory problems are shown and reported.");
        add("已切换到伐木。机器人只在附近已加载区域处理原木，会自动选用背包工具并拾取真实掉落物；工具、目标或背包阻塞状态会显示并通知。", "伐採モードに切り替えました。読み込み済みの近隣エリアだけで原木を処理し、持ち物から道具を選んで実際のドロップを拾います。道具・対象・インベントリによる停止は表示して通知します。", "Logging mode enabled. The robot processes nearby logs in loaded areas, selects a carried tool, and collects actual drops. Tool, target, or inventory problems are shown and reported.");
        add("已切换到烹饪。附近放空的农夫乐事料理锅或森罗汤锅并加热，货物槽放食材和餐具；森罗汤锅还需对应汤底与锅盖。缺料或厨具受阻会通知。", "調理モードに切り替えました。近くに空の農夫の恵みの鍋または森羅の鍋を置いて加熱し、荷物スロットに食材と調理器具を入れてください。森羅の鍋には対応するスープと蓋も必要です。材料や器具が不足すると通知します。", "Cooking mode enabled. Place and heat an empty Farmer's Delight or Shinra pot nearby, and put ingredients and utensils in the cargo slots. Shinra pots also need the matching broth and lid. Missing supplies or blocked cookware will be reported.");
        add("已切换到烧矿。请在附近放原版熔炉或原版高炉，货物槽放可熔炼的原料与燃料；机器人负责投料、补燃料和收成品，沿用炉子的真实配方与加工时间。", "精錬モードに切り替えました。近くにかまどまたは溶鉱炉を置き、荷物スロットに精錬材料と燃料を入れてください。ロボットが投入・燃料補充・回収を行い、炉本来のレシピと処理時間を使用します。", "Smelting mode enabled. Place a furnace or blast furnace nearby and put smeltable items and fuel in the cargo slots. The robot handles input, fuel, and output using the furnace's real recipes and processing time.");
        add("已切换到种地。货物槽放小麦种子、胡萝卜、马铃薯或甜菜种子；机器人在附近耕地播种、收获成熟作物并补种，有锄头时可在水边开垦。", "農作業モードに切り替えました。荷物スロットに小麦の種、ニンジン、ジャガイモ、またはビートルートの種を入れてください。近くの畑に植え、成熟作物を収穫して補充します。クワがあれば水辺を耕します。", "Farming mode enabled. Put wheat seeds, carrots, potatoes, or beetroot seeds in the cargo slots. The robot plants nearby farmland, harvests mature crops, and replants. With a hoe, it can till near water.");
        add("已切换到跟随模式。", "追従モードに切り替えました。", "Follow mode enabled.");
        add("已切换到待命模式。", "待機モードに切り替えました。", "Standby mode enabled.");
        add("指定采集已完成", "指定した採集が完了しました", "The assigned collection is complete");
        add("已暂停角色施工/生产作业，进度保留；原生炉子和发电设备仍按自身规则运行。", "建築・生産作業を一時停止しました。進捗は保持され、既存の炉や発電設備はそれぞれの仕様どおり動作を続けます。", "Construction and production are paused with progress preserved. Existing furnaces and generators continue operating normally.");
        add("已登记周期边界暂停；当前批次完成并交付后会停机，期间可用“生产线状态”查看。", "現在のバッチを完了して納品した後に停止するよう登録しました。その間は「生産ライン状態」で確認できます。", "Pause-after-cycle is set. The current batch will finish and be delivered before shutdown. Check production line status while it runs.");
        add("已恢复任务；会重新检查方块、权限、材料和通路。", "タスクを再開しました。ブロック、権限、材料、経路を再確認します。", "Task resumed. Blocks, permissions, materials, and paths will be checked again.");
        add("施工/生产已暂停，任务记录保留", "建築・生産を一時停止しました。タスク記録は保持されています。", "Construction and production are paused; the task record is preserved.");
        add("改造已暂停，恢复记录保留；完成或修复前生产保持暂停。", "改造を一時停止しました。復旧記録は保持され、完了または現場修復まで生産は停止したままです。", "Refitting is paused with its recovery record preserved. Production remains paused until refitting is completed or the site is repaired.");
        add("工作条件暂不可用", "作業条件を現在利用できません", "Work conditions are temporarily unavailable");
        add("缺料", "材料不足", "Missing supplies");
        add("输出满", "出力先が満杯です", "Output is full");
        add("供电", "電力不足", "Power issue");
        add("水洗", "水洗工程", "Washing process");
        add("堵路", "経路が塞がれています", "Path blocked");
        add("其他", "その他", "Other");
        add("按原因补入所需原料、燃料、工具或配方容器，再让机器人继续工作。", "原因を確認し、必要な材料・燃料・道具・レシピ用容器を補充してから作業を再開してください。", "Check the cause, supply the required ingredients, fuel, tools, or recipe containers, then let the robot continue.");
        add("整理成品箱、周转箱、目的箱或机器人货物格，给下一份成品留出空间。", "製品箱、搬送箱、搬入先、またはロボットの荷物スロットを整理し、次の製品用の空きを作ってください。", "Clear space in the output, transit, or destination container, or the robot's cargo slots.");
        add("更换或充电机器人电池；若提示机器缺电，检查该设备的 EU 电源和电缆。", "ロボットのバッテリーを交換または充電してください。機械の電力不足なら、その機械のEU電源とケーブルを確認してください。", "Replace or charge the robot's battery. If a machine is low on power, check its EU source and cable.");
        add("检查 Create 水洗配方、风扇/水源和置物台中的物料，再等待真实加工。", "Createの水洗レシピ、ファン・水源、置台上の材料を確認し、実際の加工を待ってください。", "Check the Create washing recipe, fan or water source, and items on the depot, then wait for processing.");
        add("清除目标与机器人之间的障碍，并确认目标旁存在可达站位。", "対象とロボットの間の障害物を取り除き、対象の近くに到達可能な立ち位置があることを確認してください。", "Clear obstacles between the robot and its target, and make sure there is a reachable position beside the target.");
        add("按原因检查目标、设备或任务现场；已投入物料和任务记录会保留。", "原因に応じて対象・設備・作業場所を確認してください。投入済みの材料とタスク記録は保持されています。", "Check the target, equipment, or work site based on the cause. Inserted materials and the task record are preserved.");
    }

    private RobotReplyLocalization() { }

    private static void add(String source, String japanese, String english) {
        FIXED.put(source, new Text(japanese, english));
    }

    /**
     * Localizes known server-authored replies. Unknown Chinese text is retained inside an explicit
     * fallback marker instead of being silently presented as if it had been translated.
     */
    public static String localize(RobotReplyLanguage language, String source) {
        if (source == null || source.isEmpty()) return source == null ? "" : source;
        RobotReplyLanguage selected = language == null ? RobotReplyLanguage.JA : language;
        if (selected == RobotReplyLanguage.ZH || isKnownTranslation(source)) return source;

        String dynamic = localizeDynamic(selected, source);
        if (dynamic != null) return dynamic;

        Text exact = FIXED.get(source);
        if (exact != null) return choose(selected, exact);
        String buildMessage = localizeBuildMessage(selected, source);
        if (buildMessage != null) return buildMessage;
        String failure = localizeFailure(selected, source);
        if (failure != null) return failure;
        return selected == RobotReplyLanguage.JA
                ? "［未登録の翻訳／原文：「" + source + "」］"
                : "[Untranslated robot message; original: “" + source + "”]";
    }

    private static String choose(RobotReplyLanguage language, Text text) {
        return language == RobotReplyLanguage.EN ? text.english() : text.japanese();
    }

    private static boolean isKnownTranslation(String source) {
        for (Text text : FIXED.values())
            if (source.equals(text.japanese()) || source.equals(text.english())) return true;
        return false;
    }

    private static String localizeDynamic(RobotReplyLanguage language, String source) {
        String[] costPrefixes = {"手机电量不足，需要 ", "机器人电池电量不足，需要 ", "虫洞设备储能不足，需要 "};
        String[] costJapanese = {"携帯電話の電力が不足しています。", "ロボットのバッテリー電力が不足しています。", "ワームホール装置の蓄電量が不足しています。"};
        String[] costEnglish = {"The phone has insufficient energy.", "The robot battery has insufficient energy.", "The wormhole device has insufficient stored energy."};
        String costSuffix = " EU。";
        for (int i = 0; i < costPrefixes.length; i++) {
            if (source.startsWith(costPrefixes[i]) && source.endsWith(costSuffix)) {
                String amount = source.substring(costPrefixes[i].length(), source.length() - costSuffix.length());
                if (isDigits(amount)) return language == RobotReplyLanguage.JA
                        ? costJapanese[i] + " 必要量：" + amount + " EU。"
                        : costEnglish[i] + " Required: " + amount + " EU.";
            }
        }

        String[] dispatchPrefixes = {"未派发烹饪任务。", "未执行工作命令。", "未派发采集任务。", "未派发新的采集任务。"};
        String[] dispatchJapanese = {"調理タスクは割り当てられませんでした。", "作業命令は実行されませんでした。", "採集タスクは割り当てられませんでした。", "新しい採集タスクは割り当てられませんでした。"};
        String[] dispatchEnglish = {"No cooking task was assigned.", "The work command was not executed.", "No collection task was assigned.", "No new collection task was assigned."};
        for (int i = 0; i < dispatchPrefixes.length; i++) {
            if (source.startsWith(dispatchPrefixes[i])) {
                String reason = source.substring(dispatchPrefixes[i].length());
                String localizedReason = reason.isEmpty() ? "" : " " + localize(language, reason);
                return language == RobotReplyLanguage.JA
                        ? dispatchJapanese[i] + localizedReason
                        : dispatchEnglish[i] + localizedReason;
            }
        }

        String buildStartedPrefix = "已开始施工：";
        String buildStartedSuffix = "。使用角色货物槽材料逐块放置；缺料或通路受阻会暂停，补齐后继续。";
        if (source.startsWith(buildStartedPrefix) && source.endsWith(buildStartedSuffix)) {
            String title = source.substring(buildStartedPrefix.length(), source.length() - buildStartedSuffix.length());
            return language == RobotReplyLanguage.JA
                    ? "建築を開始しました：「" + title + "」。ロボットの荷物スロットにある材料を使って一ブロックずつ設置します。材料不足や経路の閉塞時は一時停止し、補充・解消後に続行します。"
                    : "Construction started: “" + title + "”. The robot places blocks one at a time using cargo-slot materials. It pauses if supplies run low or the path is blocked, then resumes when resolved.";
        }

        String buildReport = localizeBuildReport(language, source);
        if (buildReport != null) return buildReport;

        String alreadyModePrefix = "机器人已经处于";
        String alreadyModeSuffix = "模式，未重复收费。";
        if (source.startsWith(alreadyModePrefix) && source.endsWith(alreadyModeSuffix)) {
            String mode = source.substring(alreadyModePrefix.length(), source.length() - alreadyModeSuffix.length());
            String translatedMode = mode(mode, language);
            if (translatedMode != null) return language == RobotReplyLanguage.JA
                    ? "ロボットはすでに" + translatedMode + "モードです。重複して請求していません。"
                    : "The robot is already in " + translatedMode + " mode. No duplicate charge was made.";
        }

        String ambiguousPrefix = "名称对应多个已注册物品，请提供具体注册ID：";
        if (source.startsWith(ambiguousPrefix)) {
            String ids = source.substring(ambiguousPrefix.length());
            return language == RobotReplyLanguage.JA
                    ? "名前に一致する登録済みアイテムが複数あります。具体的な登録IDを指定してください：" + ids
                    : "Multiple registered items match that name. Provide a specific registry ID: " + ids;
        }

        String taskPrefix = "已派发采集任务：";
        if (source.startsWith(taskPrefix)) {
            String summary = collectionSummary(language, source.substring(taskPrefix.length()));
            if (summary != null) return language == RobotReplyLanguage.JA
                    ? "採集タスクを割り当てました：" + summary : "Collection task assigned: " + summary;
        }
        String pendingCollectionPrefix = "等待采集：";
        if (source.startsWith(pendingCollectionPrefix)) {
            String summary = collectionSummary(language, source.substring(pendingCollectionPrefix.length()));
            if (summary != null) return language == RobotReplyLanguage.JA
                    ? "採集待ち：" + summary : "Waiting to collect: " + summary;
        }
        String collectionProgressPrefix = "采集进度：";
        if (source.startsWith(collectionProgressPrefix)) {
            String summary = collectionSummary(language, source.substring(collectionProgressPrefix.length()));
            if (summary != null) return language == RobotReplyLanguage.JA
                    ? "採集の進捗：" + summary : "Collection progress: " + summary;
        }
        if (source.startsWith("指定采集：")) {
            String summary = collectionSummary(language, source.substring("指定采集：".length()));
            if (summary != null) return language == RobotReplyLanguage.JA
                    ? "指定採集：" + summary : "Assigned collection: " + summary;
        }

        String cookingSummary = cookingStatusSummary(language, source);
        if (cookingSummary != null) return cookingSummary;

        int modeSeparator = source.indexOf(" / ");
        if (modeSeparator > 0) {
            String translatedMode = mode(source.substring(0, modeSeparator), language);
            if (translatedMode != null) {
                String remainder = source.substring(modeSeparator + 3);
                String orderSeparator = " / ";
                if (remainder.contains(orderSeparator)) {
                    int second = remainder.indexOf(orderSeparator);
                    String order = cookingStatusSummary(language, remainder.substring(0, second));
                    if (order != null) remainder = order + (language == RobotReplyLanguage.JA ? " / " : " / ")
                            + localize(language, remainder.substring(second + orderSeparator.length()));
                    else remainder = localize(language, remainder);
                } else remainder = localize(language, remainder);
                return translatedMode + " / " + remainder;
            }
        }
        String duplicatePrefix = "相同采集任务已在进行：";
        if (source.startsWith(duplicatePrefix)) {
            String summary = collectionSummary(language, source.substring(duplicatePrefix.length()));
            if (summary != null) return language == RobotReplyLanguage.JA
                    ? "同じ採集タスクがすでに進行中です：" + summary : "The same collection task is already running: " + summary;
        }

        String cookingPrefix = "已派发烹饪：";
        String cookingSuffix = "。只做目标菜品；按原生配方整批制作，末批可能超出，实际成品全部保留。";
        if (source.startsWith(cookingPrefix) && source.endsWith(cookingSuffix)) {
            String payload = source.substring(cookingPrefix.length(), source.length() - cookingSuffix.length());
            int split = payload.lastIndexOf('×');
            if (split > 0 && isDigits(payload.substring(split + 1))) {
                String item = payload.substring(0, split), amount = payload.substring(split + 1);
                return language == RobotReplyLanguage.JA
                        ? "調理を割り当てました：" + item + "×" + amount + "。指定の料理だけを、元のレシピどおりに一括調理します。最後のバッチは指定数を超えることがありますが、完成品はすべて保持します。"
                        : "Cooking assigned: " + item + "×" + amount + ". Only the requested food will be made in full batches using its real recipe. The final batch may exceed the amount; all output is kept.";
            }
        }

        String receivedPrefix = "已接收采集任务：";
        String receivedPickup = "，仅拾取现成物品。收集足量后待命；缺工具、权限受阻或无目标会通知你。";
        String receivedAuto = "，按目标选择方块或对应生物；鸡蛋仅巡视鸡群拾取自然掉落。收集足量后待命；缺工具、权限受阻或无目标会通知你。";
        if (source.startsWith(receivedPrefix)) {
            String suffix = source.endsWith(receivedPickup) ? receivedPickup
                    : source.endsWith(receivedAuto) ? receivedAuto : null;
            if (suffix != null) {
                String payload = source.substring(receivedPrefix.length(), source.length() - suffix.length());
                int split = payload.lastIndexOf('×');
                if (split > 0 && isDigits(payload.substring(split + 1))) {
                    String item = payload.substring(0, split), amount = payload.substring(split + 1);
                    boolean pickup = source.endsWith(receivedPickup);
                    if (language == RobotReplyLanguage.JA) return "採集タスクを受け付けました：" + item + "×" + amount
                            + (pickup ? "。既にあるアイテムだけを拾います。指定数に達したら待機します。道具不足・権限による停止・対象不在は通知します。"
                            : "。対象のブロックまたは対応する生物を探します。卵はニワトリの周囲を巡回して自然に落ちたものだけを拾います。指定数に達したら待機します。道具不足・権限による停止・対象不在は通知します。");
                    return "Collection task accepted: " + item + "×" + amount
                            + (pickup ? ". Existing items only. The robot will stand by when the amount is collected and report missing tools, permission blocks, or no target."
                            : ". It will choose matching blocks or creatures; egg tasks only patrol chickens and collect natural drops. It will stand by when the amount is collected and report missing tools, permission blocks, or no target.");
                }
            }
        }

        String collectedPrefix = "已收集 ";
        String collectedSuffix = "，物品在我的货物槽里。";
        if (source.endsWith(collectedSuffix)) {
            int nameEnd = source.indexOf('：');
            if (nameEnd > 0 && source.startsWith(collectedPrefix, nameEnd + 1)) {
                String payload = source.substring(nameEnd + 1 + collectedPrefix.length(), source.length() - collectedSuffix.length());
                int split = payload.lastIndexOf('×');
                if (split > 0 && isDigits(payload.substring(split + 1))) {
                    String name = source.substring(0, nameEnd), item = payload.substring(0, split), amount = payload.substring(split + 1);
                    return language == RobotReplyLanguage.JA
                            ? name + "：" + item + "×" + amount + "を集めました。アイテムは私の荷物スロットにあります。"
                            : name + ": Collected " + item + "×" + amount + ". The items are in my cargo slots.";
                }
            }
        }

        String cookingCompletePrefix = "烹饪完成，";
        String cookingCompleteSuffix = "；整批菜品均已收入货物槽。";
        if (source.startsWith(cookingCompletePrefix) && source.endsWith(cookingCompleteSuffix)) {
            String summary = cookingStatusSummary(language, source.substring(cookingCompletePrefix.length(), source.length() - cookingCompleteSuffix.length()));
            int nameEnd = source.indexOf('：');
            if (summary != null && nameEnd > 0) return language == RobotReplyLanguage.JA
                    ? source.substring(0, nameEnd) + "：調理が完了しました。" + summary + "。料理はすべて荷物スロットに収納しました。"
                    : source.substring(0, nameEnd) + ": Cooking complete. " + summary + ". All food was placed in the cargo slots.";
        }

        String noticeSuffix = "。任务保留，条件满足后继续。";
        if (source.endsWith(noticeSuffix)) {
            int nameEnd = source.indexOf('：');
            if (nameEnd > 0) {
                String name = source.substring(0, nameEnd);
                String reason = source.substring(nameEnd + 1, source.length() - noticeSuffix.length());
                String localizedReason = localize(language, reason);
                return language == RobotReplyLanguage.JA
                        ? name + "：作業を一時停止しています。理由：「" + localizedReason + "」。条件が整い次第、再開します。"
                        : name + ": Work is paused. Reason: “" + localizedReason + "”. It will resume when conditions allow.";
            }
        }

        String alertPhonePrefix = "工作受阻（";
        String alertPhoneSuffix = "）";
        if (source.endsWith(alertPhoneSuffix)) {
            int nameEnd = source.indexOf('：');
            if (nameEnd > 0 && source.startsWith(alertPhonePrefix, nameEnd + 1)) {
                String name = source.substring(0, nameEnd);
                String type = source.substring(nameEnd + 1 + alertPhonePrefix.length(), source.length() - alertPhoneSuffix.length());
                String translatedType = localize(language, type);
                return language == RobotReplyLanguage.JA
                        ? name + "：作業が停止しています（" + translatedType + "）"
                        : name + ": Work is blocked (" + translatedType + ")";
            }
        }

        String alertPrefix = "类型：";
        String reasonSeparator = "；原因：";
        String suggestionSeparator = "；建议：";
        String elapsedSeparator = "；持续：";
        String secondsSuffix = " 秒";
        if (source.startsWith(alertPrefix) && source.endsWith(secondsSuffix)) {
            int r = source.indexOf(reasonSeparator, alertPrefix.length());
            int s = r < 0 ? -1 : source.indexOf(suggestionSeparator, r + reasonSeparator.length());
            int e = s < 0 ? -1 : source.indexOf(elapsedSeparator, s + suggestionSeparator.length());
            if (r > 0 && s > r && e > s) {
                String type = localize(language, source.substring(alertPrefix.length(), r));
                String reason = localize(language, source.substring(r + reasonSeparator.length(), s));
                String suggestion = localize(language, source.substring(s + suggestionSeparator.length(), e));
                String seconds = source.substring(e + elapsedSeparator.length(), source.length() - secondsSuffix.length());
                if (isDigits(seconds)) return language == RobotReplyLanguage.JA
                        ? "状態：" + type + "；理由：" + reason + "；提案：" + suggestion + "；継続時間：" + seconds + "秒"
                        : "Type: " + type + "; reason: " + reason + "; suggestion: " + suggestion + "; elapsed: " + seconds + " s";
            }
        }
        return null;
    }

    private static String collectionSummary(RobotReplyLanguage language, String summary) {
        int space = summary.indexOf(' ');
        if (space <= 0) return null;
        String itemId = summary.substring(0, space);
        if (!isItemId(itemId)) return null;
        int modeStart = summary.indexOf('（', space + 1);
        if (modeStart < 0 || !summary.endsWith("）")) return null;
        String counts = summary.substring(space + 1, modeStart);
        int slash = counts.indexOf('/');
        if (slash <= 0 || !isDigits(counts.substring(0, slash)) || !isDigits(counts.substring(slash + 1))) return null;
        String collected = counts.substring(0, slash), requested = counts.substring(slash + 1);
        String mode = summary.substring(modeStart + 1, summary.length() - 1);
        String translatedMode = switch (mode) {
            case "仅拾取" -> language == RobotReplyLanguage.JA ? "拾得のみ" : "pickup only";
            case "按掉落物自动采集" -> language == RobotReplyLanguage.JA ? "ドロップを自動収集" : "automatically collect drops";
            default -> null;
        };
        if (translatedMode == null) return null;
        return itemId + " " + collected + "/" + requested + (language == RobotReplyLanguage.JA
                ? "（" + translatedMode + "）" : " (" + translatedMode + ")");
    }

    private static String cookingStatusSummary(RobotReplyLanguage language, String source) {
        String prefix = source.startsWith("指定烹饪：") ? "指定烹饪："
                : source.startsWith("烹饪进度：") ? "烹饪进度："
                : source.startsWith("烹饪完成：") ? "烹饪完成：" : "";
        String summary = prefix.isEmpty() ? source : source.substring(prefix.length());
        int space = summary.indexOf(' ');
        if (space <= 0) return null;
        String itemName = summary.substring(0, space);
        int badgeStart = summary.indexOf('（', space + 1);
        if (badgeStart < 0 || !summary.endsWith("）")) return null;
        String counts = summary.substring(space + 1, badgeStart);
        int slash = counts.indexOf('/');
        if (slash <= 0 || !isDigits(counts.substring(0, slash)) || !isDigits(counts.substring(slash + 1))) return null;
        String delivered = counts.substring(0, slash), requested = counts.substring(slash + 1);
        String badge = summary.substring(badgeStart + 1, summary.length() - 1);
        String translatedBadge;
        if (badge.equals("完成")) translatedBadge = language == RobotReplyLanguage.JA ? "完了" : "complete";
        else if (badge.startsWith("完成，超出") && isDigits(badge.substring("完成，超出".length())))
            translatedBadge = language == RobotReplyLanguage.JA ? "完了、超過" + badge.substring("完成，超出".length())
                    : "complete, exceeded by " + badge.substring("完成，超出".length());
        else if (badge.startsWith("剩余") && isDigits(badge.substring("剩余".length())))
            translatedBadge = language == RobotReplyLanguage.JA ? "残り" + badge.substring("剩余".length())
                    : badge.substring("剩余".length()) + " remaining";
        else return null;
        String translatedSummary = itemName + " " + delivered + "/" + requested + (language == RobotReplyLanguage.JA
                ? "（" + translatedBadge + "）" : " (" + translatedBadge + ")");
        if (prefix.isEmpty()) return translatedSummary;
        String translatedPrefix = switch (prefix) {
            case "指定烹饪：" -> language == RobotReplyLanguage.JA ? "調理注文：" : "Cooking order: ";
            case "烹饪进度：" -> language == RobotReplyLanguage.JA ? "調理の進捗：" : "Cooking progress: ";
            case "烹饪完成：" -> language == RobotReplyLanguage.JA ? "調理完了：" : "Cooking complete: ";
            default -> "";
        };
        return translatedPrefix + translatedSummary;
    }

    private static String mode(String chineseMode, RobotReplyLanguage language) {
        return switch (chineseMode) {
            case "跟随" -> language == RobotReplyLanguage.JA ? "追従" : "follow";
            case "伐木" -> language == RobotReplyLanguage.JA ? "伐採" : "logging";
            case "采矿" -> language == RobotReplyLanguage.JA ? "採掘" : "mining";
            case "烹饪" -> language == RobotReplyLanguage.JA ? "調理" : "cooking";
            case "烧矿" -> language == RobotReplyLanguage.JA ? "精錬" : "smelting";
            case "种地" -> language == RobotReplyLanguage.JA ? "農作業" : "farming";
            case "待命" -> language == RobotReplyLanguage.JA ? "待機" : "standby";
            case "施工" -> language == RobotReplyLanguage.JA ? "建築" : "construction";
            case "生产线" -> language == RobotReplyLanguage.JA ? "生産" : "production";
            case "生产线改造" -> language == RobotReplyLanguage.JA ? "生産ライン改修" : "line refitting";
            case "指定采集" -> language == RobotReplyLanguage.JA ? "指定採集" : "assigned collection";
            default -> null;
        };
    }

    private static String localizeBuildMessage(RobotReplyLanguage language, String source) {
        String refitIntro = "生产线换线方案：";
        if (source.startsWith(refitIntro)) {
            int titleEnd = source.indexOf('\n');
            String title = titleEnd < 0 ? source.substring(refitIntro.length())
                    : source.substring(refitIntro.length(), titleEnd);
            return language == RobotReplyLanguage.JA
                    ? "生産ラインのケーブル交換計画：「" + title + "」。詳細（元の中国語表示）：\n" + (titleEnd < 0 ? "" : source.substring(titleEnd + 1))
                    : "Production-line cable replacement plan: “" + title + "”. Details (original Chinese):\n" + (titleEnd < 0 ? "" : source.substring(titleEnd + 1));
        }

        String refitPreview = "生产线改产预览：";
        if (source.startsWith(refitPreview)) {
            int titleEnd = source.indexOf('\n');
            String title = titleEnd < 0 ? source.substring(refitPreview.length())
                    : source.substring(refitPreview.length(), titleEnd);
            return language == RobotReplyLanguage.JA
                    ? "生産ライン変更のプレビュー：「" + title + "」。計画の詳細と材料一覧（元の中国語表示）：\n" + (titleEnd < 0 ? "" : source.substring(titleEnd + 1))
                    : "Production refit preview: “" + title + "”. Plan details and material list (original Chinese):\n" + (titleEnd < 0 ? "" : source.substring(titleEnd + 1));
        }

        if (source.startsWith("已登记改产。")) return language == RobotReplyLanguage.JA
                ? "生産変更を登録しました。稼働中の場合は現在の周期を完了して納品後に一時停止します。詳細（元の中国語表示）：\n" + source.substring("已登记改产。".length())
                : "Production refit registered. If running, the current cycle will finish and be delivered before pausing. Details (original Chinese):\n" + source.substring("已登记改产。".length());

        if (source.startsWith("已换线 ")) return language == RobotReplyLanguage.JA
                ? "ケーブル交換が完了しました。詳細（元の中国語表示）：\n" + source
                : "Cable replacement complete. Details (original Chinese):\n" + source;

        if (source.startsWith("待确认方案：") || source.startsWith("任务：")) {
            String prefix = source.startsWith("待确认方案：") ? "待确认方案：" : "任务：";
            int titleEnd = source.indexOf('\n');
            String title = titleEnd < 0 ? source.substring(prefix.length()) : source.substring(prefix.length(), titleEnd);
            String header = prefix.equals("待确认方案：")
                    ? (language == RobotReplyLanguage.JA ? "確認待ちの建築計画" : "Construction plan awaiting confirmation")
                    : (language == RobotReplyLanguage.JA ? "タスク" : "Task");
            return header + ": “" + title + "”\n"
                    + (language == RobotReplyLanguage.JA ? "詳細（元の中国語表示）：\n" : "Details (original Chinese):\n")
                    + (titleEnd < 0 ? "" : source.substring(titleEnd + 1));
        }
        return null;
    }

    private static String localizeBuildReport(RobotReplyLanguage language, String source) {
        if (source.startsWith("未执行：")) return language == RobotReplyLanguage.JA
                ? "処理を実行できませんでした。理由（元の中国語表示）：「" + source.substring("未执行：".length()) + "」"
                : "The request was not executed. Reason (original Chinese): “" + source.substring("未执行：".length()) + "”";
        if (source.startsWith("场地有") && source.endsWith("。")) return language == RobotReplyLanguage.JA
                ? "建築場所に障害物があります。詳細（元の中国語表示）：「" + source + "」"
                : "The construction site is obstructed. Details (original Chinese): “" + source + "”";
        if (source.startsWith("新增部件位置已有其他方块 ") || source.startsWith("改产部件超出高度/边界或目标区块未加载 @ "))
            return language == RobotReplyLanguage.JA
                    ? "改造を中止しました。現場のブロックまたは境界が条件を満たしません。詳細（元の中国語表示）：「" + source + "」"
                    : "Refit stopped because the site block or boundary does not meet requirements. Details (original Chinese): “" + source + "”";
        if (source.startsWith("电缆目标区块未加载或越界；") || source.startsWith("电缆 ") && source.contains("拒绝自动拆除"))
            return language == RobotReplyLanguage.JA
                    ? "ケーブル交換を中止しました。チャンク・境界またはケーブル状態を確認してください。詳細（元の中国語表示）：「" + source + "」"
                    : "Cable replacement stopped. Check the chunk, world boundary, or cable state. Details (original Chinese): “" + source + "”";
        if (source.startsWith("方案越过世界边界或建筑高度限制。") || source.startsWith("场地包含未加载区块；"))
            return language == RobotReplyLanguage.JA
                    ? "建築計画を実行できません。現場の境界またはチャンクの状態を確認してください。詳細（元の中国語表示）：「" + source + "」"
                    : "The construction plan cannot run. Check the site boundary and chunk status. Details (original Chinese): “" + source + "”";
        return null;
    }

    private static String localizeFailure(RobotReplyLanguage language, String source) {
        String[] failureStarts = {
                "角色当前离线或不可访问", "角色离线、超出操作距离", "方案已过期", "工业任务恢复记录损坏",
                "改造记录属于原主人", "当前角色没有已完成的生产线", "请在换线描述中", "改产需要一条已完成",
                "已有未完成改造记录", "当前生产线已变化", "角色需处于生产线", "角色离生产线太远",
                "加工进度或已备物料记录", "生产线已备物料与角色货物槽", "启动换线需要", "存在未完成的生产线改造",
                "已有建造或生产线记录", "角色已远离预览场地", "启动施工需要", "没有建造或生产线任务",
                "只有正在运行的新版生产线", "没有属于你且在本维度", "角色缺电", "这座房屋已完成",
                "未知建造操作", "未执行：", "场地有", "新增部件位置已有其他方块", "改产部件超出高度/边界",
                "电缆目标区块未加载", "电缆 ", "方案越过世界边界", "场地包含未加载区块", "原锅或待投原料无法安全核对",
                "当前已兼容的料理锅没有", "请选择有效菜品", "烹饪任务状态已变化", "采集模式无效", "目标物品 ID 无效",
                "不能把空气设为采集目标", "只有机器人的所有者", "机器人需要至少200 EU", "机器人至少需要200 EU",
                "机器人当前电量不足", "机器人未加载、离线或权限", "操作过快"
        };
        for (String start : failureStarts) if (source.startsWith(start))
            return language == RobotReplyLanguage.JA
                    ? "要求を完了できませんでした。詳細（元の中国語表示）：「" + source + "」"
                    : "The request could not be completed. Details (original Chinese): “" + source + "”";
        return null;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }

    private static boolean isItemId(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon != value.lastIndexOf(':') || colon == value.length() - 1) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') continue;
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.' && c != '/') return false;
        }
        return true;
    }
}
