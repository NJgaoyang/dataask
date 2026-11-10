import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';

// PC and mobile remain two views of ONE Spring Boot/MySQL session system.
const pc=readFileSync('web/src/App.vue','utf8');
const app=readFileSync('web/public/miniapp/index.html','utf8');
const chat=readFileSync('src/main/java/com/dataask/chat/ChatController.java','utf8');
const schema=readFileSync('src/main/resources/schema.sql','utf8');
const css=readFileSync('web/src/composer-resize.css','utf8');
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
  [app,'state.chatEpoch++'],
  [pc,'sourceMessageId: sessionId.value ? sourceMessageId : null'],
  [app,'sourceMessageId:state.sessionId&&follow?'],
  [chat,'requireSession(session, username)'],
  [chat,'input.sourceMessageId(), session'],
  [schema,'CREATE TABLE IF NOT EXISTS da_sessions'],
  [schema,'CREATE TABLE IF NOT EXISTS da_messages'],
  [pc,'@pointerdown="beginComposerDrag"'],
  [pc,'@keydown.up.prevent="adjustComposerHeight(24)"'],
  [css,'grid-template-rows:52px minmax(0,1fr) var(--da-composer-height'],
  [entry,"import './composer-resize.css'"]
]) assert(source.includes(token),'Cross-device session / composer contract missing: '+token);

assert(css.includes('cursor:ns-resize'), 'Composer must support vertical dragging');
assert(!/data-action="mic"/.test(app), 'Miniapp must not provide microphone input');
console.log('DataAsk: single backend, shared sessions, fresh-chat isolation, cross-device refresh and resizable PC composer OK');
