import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';

// PC and mobile remain two views of ONE Spring Boot/MySQL session system.
const pc=readFileSync('web/src/App.vue','utf8');
const app=readFileSync('web/public/miniapp/index.html','utf8');
const chat=readFileSync('src/main/java/com/dataask/chat/ChatController.java','utf8');
const settings=readFileSync('src/main/java/com/dataask/config/RuntimeConfigService.java','utf8');
const schema=readFileSync('src/main/resources/schema.sql','utf8');
const css=readFileSync('web/src/pc-design-system.css','utf8');
const entry=readFileSync('web/src/main.js','utf8');

for(const [source,token] of [
  [pc,"api.get('/chat/sessions')"],
  [pc,"api.post('/chat/ask'"],
  [app,"api('/api/chat/sessions')"],
  [app,"api('/api/chat/ask','POST'"],
  [app,"function newConversation(redraw=true)"],
  [app,'data-action="new-session"'],
  [pc,'class="sidebar-new da-new-conversation"'],
  [pc,'@click="newConversation"'],
  [pc,'window.addEventListener(\'focus\', refreshSharedHistory)'],
  [app,"window.addEventListener('focus',refreshSharedHistory)"],
  [pc,'conversationVersion++'],
  [pc,'if (busy.value) stopAsk()'],
  [pc,'epoch !== authEpoch'],
  [pc,'sentAuthorization !== currentAuthorization'],
  [app,'state.chatEpoch++'],
  [app,'authEpoch!==state.authEpoch'],
  [app,"token===sessionStorage.getItem('dataask_token')"],
  [pc,'sourceMessageId: sessionId.value ? sourceMessageId : null'],
  [app,'sourceMessageId:state.sessionId&&follow?'],
  [chat,'requireSession(session, username)'],
  [chat,'WHERE username=?'],
  [chat,'WHERE id=? AND username=?'],
  [chat,'INSERT INTO da_sessions(id,username,title)'],
  [chat,'input.sourceMessageId(), session'],
  [settings,'SELECT setting_key, setting_value FROM da_app_settings'],
  [settings,'effective(m,"ai.baseUrl",yamlAi.baseUrl())'],
  [schema,'CREATE TABLE IF NOT EXISTS da_sessions'],
  [schema,'CREATE TABLE IF NOT EXISTS da_messages'],
  [pc,'@pointerdown="beginComposerDrag"'],
  [pc,'@keydown.up.prevent="adjustComposerHeight(24)"'],
  [css,'var(--da-composer-height'],
  [css,'.da-composer-resize-handle'],
  [entry,"import './pc-design-system.css'"]
]) assert(source.includes(token),'Cross-device session / composer contract missing: '+token);

assert(css.includes('cursor:ns-resize'), 'Composer must support vertical dragging');
assert(!/data-action="mic"/.test(app), 'Miniapp must not provide microphone input');
const miniLogout=app.slice(app.indexOf('function logout(){'),app.indexOf('async function loadHistory'));
const miniCancelAt=miniLogout.indexOf("api('/api/chat/cancel'");
const miniTokenClearAt=miniLogout.indexOf("sessionStorage.removeItem('dataask_token')");
assert(miniCancelAt>=0 && miniTokenClearAt>=0 && miniCancelAt<miniTokenClearAt,
  'Miniapp must start cancellation while the authentication token is still available');
console.log('DataAsk: shared global model, per-user session isolation, guarded cross-device refresh and resizable PC composer OK');
