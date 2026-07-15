import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import ts from 'typescript'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse as parseTemplate, NodeTypes } from '@vue/compiler-dom'
import config from '../i18n-hardcode.config.mjs'

const root = path.resolve(import.meta.dirname, '..')
const srcRoot = path.join(root, 'src')
const han = /\p{Script=Han}/u
const words = /[\p{L}]/u
const allowed = new Set(config.allowedText.map(item => item.value))
const allowedPatterns = config.allowedPatterns.map(item => new RegExp(item.pattern, 'u'))
const violations = []

function isAllowed(value) {
  const normalized = value.replace(/\s+/g, ' ').trim()
  return !normalized || allowed.has(normalized) || allowedPatterns.some(pattern => pattern.test(normalized))
}

function report(file, source, offset, text, kind) {
  const before = source.slice(0, offset)
  const line = before.split('\n').length
  const column = offset - before.lastIndexOf('\n')
  violations.push({ file: path.relative(root, file).replaceAll('\\', '/'), line, column, text: text.replace(/\s+/g, ' ').trim(), kind })
}

async function files(dir) {
  const result = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    const relative = path.relative(srcRoot, full).replaceAll('\\', '/')
    if (entry.isDirectory()) {
      if (relative === 'locales' || relative === 'test' || relative.includes('__tests__')) continue
      result.push(...await files(full))
    } else if (/\.(vue|ts)$/.test(entry.name) && relative !== 'i18n.ts' && !/\.(?:test|spec)\.ts$/.test(entry.name)) result.push(full)
  }
  return result
}

function checkTs(file, source, baseOffset = 0, wholeSource = source) {
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  function visit(node) {
    if ((ts.isStringLiteralLike(node) || ts.isTemplateLiteral(node)) && han.test(node.getText(ast))) {
      report(file, wholeSource, baseOffset + node.getStart(ast), node.getText(ast), 'Chinese string')
      return
    }
    if (ts.isStringLiteralLike(node) && words.test(node.text) && !isAllowed(node.text)) {
      const parent = node.parent
      const call = ts.isCallExpression(parent) ? parent : undefined
      const callee = call?.expression.getText(ast) || ''
      const property = ts.isPropertyAssignment(parent) ? parent.name.getText(ast).replace(/['"]/g, '') : ''
      const userFacingCall = /^(?:toast\.(?:success|error|info|warning)|confirm)$/.test(callee)
      const userFacingProperty = ['label', 'title', 'description', 'placeholder', 'confirmText'].includes(property)
      if (userFacingCall || userFacingProperty) {
        report(file, wholeSource, baseOffset + node.getStart(ast), node.text, userFacingCall ? `Raw ${callee} text` : `Raw ${property} property`)
        return
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(ast)
}

function checkVue(file, source) {
  const { descriptor, errors } = parseSfc(source, { filename: file })
  if (errors.length) throw new Error(`Cannot parse ${file}: ${errors.join(', ')}`)
  if (descriptor.scriptSetup) checkTs(file, descriptor.scriptSetup.content, descriptor.scriptSetup.loc.start.offset, source)
  if (!descriptor.template) return
  const block = descriptor.template
  const ast = parseTemplate(block.content, { comments: true })
  const walk = node => {
    if (node.type === NodeTypes.TEXT && words.test(node.content) && !isAllowed(node.content)) {
      report(file, source, block.loc.start.offset + node.loc.start.offset, node.content, 'Raw template text')
    }
    if (node.type === NodeTypes.ELEMENT) {
      for (const prop of node.props) {
        if (prop.type === NodeTypes.ATTRIBUTE && prop.value && config.userFacingAttributes.includes(prop.name) && words.test(prop.value.content) && !isAllowed(prop.value.content)) {
          report(file, source, block.loc.start.offset + prop.loc.start.offset, prop.value.content, `Raw ${prop.name} attribute`)
        }
      }
    }
    for (const child of node.children || []) walk(child)
  }
  walk(ast)
}

for (const file of await files(srcRoot)) {
  const source = await readFile(file, 'utf8')
  if (file.endsWith('.vue')) checkVue(file, source)
  else checkTs(file, source)
}

if (violations.length) {
  for (const item of violations) console.error(`${item.file}:${item.line}:${item.column} ${item.kind}: ${JSON.stringify(item.text)}`)
  console.error(`\nFound ${violations.length} hard-coded user-facing text violation(s).`)
  process.exit(1)
}
console.log('No hard-coded user-facing text found.')
