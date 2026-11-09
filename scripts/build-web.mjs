import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('构建需要 Node.js 22 或更高版本')
let sha = 'unknown'
try { sha = execFileSync('git', ['rev-parse', '--short=8', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim() } catch {}
const dirty = spawnSync('git', ['diff', '--quiet', 'HEAD'], { cwd: root }).status === 1
if (dirty) sha += '-dirty'
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
function run(args, env = process.env) {
  const result = spawnSync(npm, args, { cwd: resolve(root, 'web'), env, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.error || result.status !== 0) throw new Error(`npm ${args.join(' ')} 执行失败：${result.error?.message || result.status}`)
}
run([existsSync(resolve(root, 'web/package-lock.json')) ? 'ci' : 'install', '--no-audit', '--no-fund'])
run(['run', 'build'], { ...process.env, DATAASK_BUILD_SHA: sha })
const build = { buildSha: sha, builtAt: new Date().toISOString(), uiLayout: 'desktop-three-pane',
  modelManagement: 'web', configStorage: 'mysql', catalogVersion: 'table-column-selector-v1' }
mkdirSync(resolve(root, 'web/dist'), { recursive: true })
writeFileSync(resolve(root, 'web/dist/build-info.json'), JSON.stringify(build) + '\n')
rmSync(resolve(root, 'target/classes/static'), { recursive: true, force: true })
console.log(`DataAsk 前端构建版本: ${sha}`)
