let state = null;
let selectedCalibration = null;
let lastExport = null;

const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, options = {}) {
  const response = await fetch(path, {headers: {'Content-Type':'application/json'}, ...options});
  const body = await response.json();
  if (!response.ok) { toast((body && body.message) || '请求失败'); throw new Error(body.code); }
  return body;
}
function toast(message) { $('toast').textContent = message; $('toast').style.display = 'block'; setTimeout(() => $('toast').style.display = 'none', 4200); }
function decodePayload(viewOrInput) { return viewOrInput.payload; }
function calibrationComputation(view) { return view.payload; }
function curvePoints() {
  const curves = [...Object.values(state.inputs)].filter(i => i.kind === 'curve').sort((a,b)=>a.seq-b.seq);
  return curves.map(v => ({version:v, payload:v.payload}));
}
function stableId(value) { return `<span class="stable-id" title="稳定身份">${esc(value)}</span>`; }
function statusBadge(value) { return `<span class="badge status-${esc(value)}">${esc(value)}</span>`; }
function asObject(value) { return typeof value === 'string' ? JSON.parse(value) : value; }

async function loadState() {
  const seq = $('asOf').value.trim();
  state = await api('/api/state' + (seq ? `?asOfSeq=${encodeURIComponent(seq)}` : ''));
  if (!selectedCalibration || ![...Object.values(state.derived)].some(d => d.eventId === selectedCalibration)) {
    const first = [...Object.values(state.derived)].filter(d => d.kind === 'calibration').sort((a,b)=>a.seq-b.seq)[0];
    selectedCalibration = first ? first.eventId : null;
  }
  render();
}
function render() {
  if (!state) return;
  $('projectTitle').textContent = `${state.title} · 事件 ${state.maxSeq} · ${state.projectId}`;
  renderDiagnostics(); renderCurves(); renderDensitySelector(); renderDensity(); renderIntervals();
  renderSequence(); renderSources(); renderDecisions(); renderEvents(); renderReservoirs();
}
function renderDiagnostics() {
  const fixed = state.diagnostics.length ? state.diagnostics.map(d => `<tr><td>${esc(d.code)}</td><td>${esc(d.subjectId)}</td><td>${esc(d.message)}</td><td>${stableId(d.eventId || '')}</td></tr>`).join('') : '<tr><td>暂无诊断；固定版本重放通过。</td></tr>';
  $('diagnostics').innerHTML = `<table><thead><tr><th>代码</th><th>对象</th><th>信息</th><th>事件</th></tr></thead><tbody>${fixed}</tbody></table>`;
}
function renderCurves() {
  const versions = curvePoints();
  const width = 720, height = 280, margin = 48;
  const all = versions.flatMap(v => v.payload.points);
  if (!all.length) { $('curveSvg').innerHTML = ''; $('curveList').textContent = '无曲线'; return; }
  const xs = all.map(p=>p.calendarBP), ys = all.map(p=>p.radiocarbonBP);
  const minX=Math.min(...xs), maxX=Math.max(...xs), minY=Math.min(...ys), maxY=Math.max(...ys);
  const x = bp => margin + (maxX-bp)/(maxX-minX)*(width-2*margin);
  const y = age => height-margin-(age-minY)/(maxY-minY)*(height-2*margin);
  const paths = versions.map((v,idx) => {
    const color = idx === versions.length-1 ? '#0f766e' : '#94a3b8';
    const d = v.payload.points.map((p,i)=>`${i?'L':'M'}${x(p.calendarBP).toFixed(1)},${y(p.radiocarbonBP).toFixed(1)}`).join('');
    return `<path class="line-curve" style="stroke:${color}" data-id="${esc(v.version.versionId)}" d="${d}"/>`;
  }).join('');
  const boundary = (minX <= 1950 && maxX >= 1950) ? `<line x1="${x(1950)}" x2="${x(1950)}" y1="${margin}" y2="${height-margin}" stroke="#dc2626" stroke-dasharray="4 4"/><text x="${x(1950)+4}" y="${margin+12}" fill="#dc2626" font-size="11">BCE/CE 1950 BP</text>` : '';
  $('curveSvg').innerHTML = `<svg viewBox="0 0 ${width} ${height}">${axes(width,height,margin,minX,maxX,minY,maxY,'calendar BP（老→新）','14C BP')}${boundary}${paths}</svg>`;
  $('curveList').innerHTML = versions.map(v => `<div onclick="traceInput('${esc(v.version.versionId)}')"><b>${esc(v.payload.name)}</b> ${statusBadge(v.version.supersededBy ? 'STALE_DEPENDENCY_CHANGED' : 'CURRENT')}${stableId(v.version.versionId)}<span class="small">点 ${v.payload.points.length}；来源 ${esc(v.payload.provenance.reference)}</span></div>`).join('');
}
function axes(w,h,m,minX,maxX,minY,maxY,xLabel,yLabel) {
  let ticks = '';
  for (let bp=Math.ceil(minX/50)*50; bp<=maxX; bp+=50) {
    const xx=m+(maxX-bp)/(maxX-minX)*(w-2*m); ticks += `<line x1="${xx}" x2="${xx}" y1="${h-m}" y2="${h-m+5}" class="axis"/><text x="${xx-18}" y="${h-m+20}" font-size="10">${bp}</text>`;
  }
  for (let i=0;i<4;i++) { const val=minY+(maxY-minY)*i/3; const yy=h-m-i/3*(h-2*m); ticks += `<line x1="${m-5}" x2="${m}" y1="${yy}" y2="${yy}" class="axis"/><text x="${m-42}" y="${yy+4}" font-size="10">${Math.round(val)}</text>`; }
  return `<line x1="${m}" x2="${w-m}" y1="${h-m}" y2="${h-m}" class="axis"/><line x1="${m}" x2="${m}" y1="${m}" y2="${h-m}" class="axis"/><text x="${w/2-40}" y="${h-8}" font-size="11">${xLabel}</text><text transform="translate(14 ${h/2+60}) rotate(-90)" font-size="11">${yLabel}</text>${ticks}`;
}
function renderDensitySelector() {
  const calibrations = [...Object.values(state.derived)].filter(d=>d.kind==='calibration').sort((a,b)=>a.seq-b.seq);
  $('densitySelect').innerHTML = calibrations.map(d => `<option value="${esc(d.eventId)}" ${d.eventId===selectedCalibration?'selected':''}>${esc(d.subjectId)} · ${esc(d.status)}</option>`).join('');
  $('densitySelect').onchange = e => { selectedCalibration=e.target.value; renderDensity(); renderIntervals(); };
}
function renderDensity() {
  const view = state.derived[selectedCalibration];
  if (!view) { $('densitySvg').innerHTML=''; return; }
  const c = calibrationComputation(view);
  const width=720,height=280,margin=48, xs=c.density.map(p=>p.calendarBP), ys=c.density.map(p=>p.density);
  const minX=Math.min(...xs),maxX=Math.max(...xs),maxY=Math.max(...ys);
  const x=bp=>margin+(maxX-bp)/(maxX-minX)*(width-2*margin), y=v=>height-margin-v/maxY*(height-2*margin);
  const line=c.density.map((p,i)=>`${i?'L':'M'}${x(p.calendarBP).toFixed(1)},${y(p.density).toFixed(1)}`).join('');
  const area=`M${x(minX)},${height-margin} `+c.density.map(p=>`L${x(p.calendarBP).toFixed(1)},${y(p.density).toFixed(1)}`).join(' ')+` L${x(maxX)},${height-margin} Z`;
  const intervals=c.credibleIntervals.map(iv=>`<rect class="interval" data-id="${esc(iv.intervalId)}" x="${x(iv.startBP)}" y="${margin}" width="${Math.max(1,x(iv.endBP)-x(iv.startBP))}" height="${height-2*margin}" onclick="traceInterval('${esc(view.eventId)}','${esc(iv.intervalId)}')"/>`).join('');
  const modes=c.modes.map(mode=>`<circle cx="${x(mode.centerBP)}" cy="${y(mode.height)}" r="4" fill="#dc2626" data-id="${esc(mode.modeId)}"/>`).join('');
  const boundary=(minX<=1950&&maxX>=1950)?`<line x1="${x(1950)}" x2="${x(1950)}" y1="${margin}" y2="${height-margin}" stroke="#dc2626" stroke-dasharray="4 4"/><text x="${x(1950)+4}" y="${margin+12}" font-size="11" fill="#dc2626">BCE/CE</text>`:'';
  $('densitySvg').innerHTML=`<svg viewBox="0 0 ${width} ${height}">${axes(width,height,margin,minX,maxX,0,maxY,'calendar BP（老→新）','归一化密度')}${intervals}<path class="area-density" d="${area}"/><path class="line-density" d="${line}"/>${modes}${boundary}</svg>`;
}
function renderReservoirs() {
  const reservoirs = [...Object.values(state.inputs)].filter(i=>i.kind==='reservoir').sort((a,b)=>a.seq-b.seq);
  $('reservoirList').innerHTML = reservoirs.map(v => `<div onclick="traceInput('${esc(v.versionId)}')"><b>${esc(v.payload.name)}</b> ${statusBadge(v.supersededBy?'STALE_DEPENDENCY_CHANGED':'CURRENT')}${stableId(v.versionId)}${v.payload.scenarios.map(s=>`<span class="badge">${esc(s.label)}: ΔR ${s.offsetBP} ± ${s.sigmaBP} BP, w=${s.weight}</span>`).join('')}</div>`).join('');
}
function renderIntervals() {
  const calibrations = [...Object.values(state.derived)].filter(d=>d.kind==='calibration').sort((a,b)=>a.seq-b.seq);
  const rows = calibrations.flatMap(view => view.payload.credibleIntervals.map(iv => ({view,iv}))).map(x => `<tr><td>${esc(x.view.subjectId)}</td><td>${esc(x.iv.startLabel)}–${esc(x.iv.endLabel)}</td><td>${(x.iv.probability*100).toFixed(1)}%</td><td>${x.iv.crossesBCE_CE?'跨界':'同侧'}</td><td>${statusBadge(x.view.status)}</td><td>${stableId(x.iv.intervalId)}<button onclick="traceInterval('${esc(x.view.eventId)}','${esc(x.iv.intervalId)}')">积分证据</button></td></tr>`).join('');
  $('intervalTable').innerHTML=`<table><thead><tr><th>样本</th><th>区间</th><th>概率</th><th>BCE/CE</th><th>状态</th><th>身份/动作</th></tr></thead><tbody>${rows || '<tr><td>暂无候选</td></tr>'}</tbody></table>`;
}
function renderSequence() {
  const sequence = [...Object.values(state.derived)].filter(d=>d.kind==='sequence').sort((a,b)=>a.seq-b.seq).at(-1);
  if (!sequence) { $('sequenceSvg').innerHTML=''; $('sequenceTable').innerHTML='暂无层序评估'; return; }
  const samples = new Set(sequence.payload.assessments.flatMap(a=>[a.youngerSampleId,a.olderSampleId]));
  const pos = {};
  [...samples].sort().forEach((id,i)=>pos[id]={x:130+i*220,y:120});
  const nodes=[...samples].map(id=>`<g onclick="traceObject('${esc(id)}')"><rect class="node" x="${pos[id].x-70}" y="${pos[id].y-28}" width="140" height="56" rx="8"/><text x="${pos[id].x-58}" y="${pos[id].y-4}" font-size="12">${esc(id)}</text><text x="${pos[id].x-58}" y="${pos[id].y+14}" font-size="10">calendar density</text></g>`).join('');
  const edges=sequence.payload.assessments.map(a=>{
    const y=pos[a.youngerSampleId], o=pos[a.olderSampleId], conflict=a.status==='CONTRADICTED';
    return `<line class="relation ${conflict?'conflict':''}" x1="${y.x+70}" y1="${y.y}" x2="${o.x-70}" y2="${o.y}"/><text x="${(y.x+o.x)/2-58}" y="${(y.y+o.y)/2-8}" font-size="11" class="${conflict?'':''}">P(顺序)=${(a.orderingProbability*100).toFixed(1)}% ${conflict?'冲突':''}</text>`;
  }).join('');
  $('sequenceSvg').innerHTML=`<svg viewBox="0 0 640 220"><defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#64748b"/></marker></defs>${nodes}${edges}</svg>`;
  const candidates = sequence.payload.candidates.map(c => `<tr><td>${esc(c.kind)}</td><td>${esc(c.description)}</td><td>${c.probability==null?'—':(c.probability*100).toFixed(1)+'%'}</td><td>${stableId(c.candidateId)}<button onclick="traceCandidate('${esc(sequence.eventId)}','${esc(c.candidateId)}')">追踪</button><button onclick="acceptCandidate('${esc(sequence.subjectId)}','${esc(c.candidateId)}','${esc(c.kind)}')">接受</button><button class="danger" onclick="rejectCandidate('${esc(sequence.subjectId)}','${esc(c.candidateId)}','${esc(c.kind)}')">否定</button></td></tr>`).join('');
  $('sequenceTable').innerHTML=`${statusBadge(sequence.status)}${stableId(sequence.eventId)}<table><thead><tr><th>候选调整</th><th>说明</th><th>概率</th><th>身份/裁定</th></tr></thead><tbody>${candidates||'<tr><td>无冲突候选；测量未被改写</td></tr>'}</tbody></table>`;
}
async function candidateDecision(candidateId, kind, action, rationale) {
  const conflictKey = kind === 'FORCE_RELATION' || kind === 'RELAX_RELATION' ? 'profile-a7-ordering' : candidateId;
  const payload = {decisionId:`decision-${action.toLowerCase()}-${candidateId.slice(-10)}-${state.maxSeq+1}`, action, targetType:'sequence-candidate', targetId:candidateId, subjectId:'sequence-profile-a7', rationale, conflictKey};
  await api('/api/decide', {method:'POST', body:JSON.stringify(payload)});
  await loadState(); toast('人工决定已写入历史');
}
function acceptCandidate(subjectId,candidateId,kind) { return candidateDecision(candidateId,kind,'ACCEPT','审阅界面接受候选，作为新事件保留'); }
function rejectCandidate(subjectId,candidateId,kind) { return candidateDecision(candidateId,kind,'REJECT','审阅界面否定候选，保留拒绝原因'); }

function renderSources() {
  const rows=[...Object.values(state.inputs)].sort((a,b)=>a.seq-b.seq).map(v=>{
    const raw = rawQuantities(v);
    return `<tr><td>${esc(v.kind)}</td><td>${esc(v.subjectId)}</td><td>${statusBadge(v.supersededBy?'STALE_DEPENDENCY_CHANGED':'CURRENT')}</td><td>${raw}${stableId(v.versionId)}<div class="small">父版本：${v.parentVersionId?esc(v.parentVersionId):'—'}<br>来源：${(v.directSources||[]).map(esc).join(', ')}</div></td></tr>`;
  }).join('');
  $('sourceTable').innerHTML=`<table><thead><tr><th>类型</th><th>对象</th><th>状态</th><th>原始表示/身份</th></tr></thead><tbody>${rows}</tbody></table>`;
}
function rawQuantities(v) {
  if (v.kind !== 'measurement') return '';
  const p=v.payload;
  return `<div>14C: <code>${esc(p.age14CBP.rawRepresentation)} ${esc(p.age14CBP.unit)}</code>；σ: <code>${esc(p.sigma14CBP.rawRepresentation)} ${esc(p.sigma14CBP.unit)}</code></div>`;
}
function renderDecisions() {
  const rows=[...Object.values(state.decisions)].sort((a,b)=>a.seq-b.seq).map(d=>`<tr><td>${esc(d.action)}</td><td>${esc(d.targetType)}</td><td>${d.reversed?'已撤销/仍可查看':'有效'}</td><td>${stableId(d.eventId)}<div class="small">${esc(d.payload.decision?.rationale || '')}</div><button onclick="undoDecision('${esc(d.eventId)}')">撤销</button></td></tr>`).join('');
  const conflicts=[...Object.values(state.derived)].filter(d=>d.kind==='decision-conflict').map(d=>`<div>${statusBadge('CONFLICT')}${esc(d.payload.conflictKey)}${stableId(d.eventId)}</div>`).join('');
  $('decisionTable').innerHTML=(conflicts||'<div class="small">当前无互斥决定。</div>')+`<table><thead><tr><th>动作</th><th>目标</th><th>状态</th><th>身份/历史</th></tr></thead><tbody>${rows||'<tr><td>暂无</td></tr>'}</tbody></table>`;
}
async function undoDecision(eventId) {
  await api('/api/undo-decision',{method:'POST',body:JSON.stringify({decisionEventId:eventId,rationale:'审阅界面撤销；旧事件保留'})});
  await loadState(); toast('撤销已作为新事件写入');
}
function renderEvents() {
  const rows=state.events.map(e=>`<tr onclick="traceEvent('${esc(e.id)}')"><td>${e.seq}</td><td>${esc(e.type)}</td><td>${esc(e.subjectId)}</td><td>${stableId(e.id)}</td></tr>`).join('');
  $('eventTable').innerHTML=`<table><thead><tr><th>#</th><th>类型</th><th>对象</th><th>身份</th></tr></thead><tbody>${rows}</tbody></table>`;
}
function setTrace(object) { $('trace').textContent = JSON.stringify(object, null, 2); }
function findEvent(id) { return state.events.find(e=>e.id===id); }
function traceEvent(id) { const e=findEvent(id); setTrace({event:e, stateAtSeq:e.seq}); }
function traceInput(versionId) { const v=state.inputs[versionId]; setTrace({input:v, event:findEvent(v.eventId)}); }
function traceObject(id) { const related=[...Object.values(state.derived)].filter(d=>d.subjectId===id); setTrace({subject:id, derived:related}); }
function traceInterval(calibrationEventId, intervalId) {
  const view=state.derived[calibrationEventId]; const iv=view.payload.credibleIntervals.find(x=>x.intervalId===intervalId);
  setTrace({interval:iv, calibration:view.payload, sourceEvents:view.directSources.map(findEvent), algorithmVersion:view.algorithmVersion, parameterFingerprint:view.parameterFingerprint});
}
function traceCandidate(sequenceEventId,candidateId) {
  const view=state.derived[sequenceEventId]; const c=view.payload.candidates.find(x=>x.candidateId===candidateId);
  setTrace({candidate:c, sequence:view.payload, sourceEvents:view.directSources.map(findEvent), algorithmVersion:view.algorithmVersion, parameterFingerprint:view.parameterFingerprint});
}

$('loadCase').onclick = async () => { await api('/api/casepack/initialize',{method:'POST'}); await loadState(); toast('离线案例已导入，可核对全部来源'); };
$('stage1').onclick = async () => { await api('/api/casepack/stages/stage-1-parameter-revision',{method:'POST'}); await loadState(); toast('储库分布参数已以父版本关系修订'); };
$('stage2').onclick = async () => { await api('/api/casepack/stages/stage-2-local-curve-revision',{method:'POST'}); await loadState(); toast('曲线 v2 仅影响局部依赖区间'); };
$('stage3').onclick = async () => { await api('/api/casepack/stages/stage-3-conflicting-decisions',{method:'POST'}); await loadState(); toast('互斥决定已保留为冲突事件'); };
$('export').onclick = async () => { lastExport = await api('/api/export'); const blob=new Blob([JSON.stringify(lastExport,null,2)],{type:'application/json'}); const a=document.createElement('a'); a.href=URL.createObjectURL(blob); a.download='radiocarbon-workbench-export.json'; a.click(); toast('导出完成；身份和顺序在 JSON 事件日志中'); };
$('importExport').onclick = async () => { if(!lastExport) lastExport=await api('/api/export'); const result=await api('/api/import',{method:'POST',body:JSON.stringify(lastExport)}); await loadState(); toast(`模拟删除后导入：${result.importedEvents} 个事件，身份稳定`); };
$('reload').onclick = loadState;
$('asOfApply').onclick = loadState;
$('asOfClear').onclick = () => { $('asOf').value=''; loadState(); };
loadState().catch(()=>toast('项目为空：先导入离线案例'));
