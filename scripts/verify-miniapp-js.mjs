import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';

const path = 'web/public/miniapp/index.html';
const html = readFileSync(path, 'utf8');
const inline = html.match(/<script>([\s\S]*?)<\/script>/i)?.[1];
if (!inline) throw new Error('V2 miniapp inline application script is missing');
new Script(inline, { filename: 'miniapp-inline.js' });
for (const route of ['/api/auth/login','/api/chat/ask','/api/mini/bootstrap','/api/mini/charts']) {
  if (!html.includes(route)) throw new Error('Missing live integration: '+route);
}
if (html.includes('8,463,520') || html.includes('数据为交互原型演示')) {
  throw new Error('Static business demo data must not be published');
}
console.log('V2 miniapp inline JavaScript syntax and API wiring OK');
