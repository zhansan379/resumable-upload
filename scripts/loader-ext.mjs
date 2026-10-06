// 测试台 loader：为无扩展名的相对导入补 .js（Node 严格 ESM 不做 Vite 式扩展名解析）
export async function resolve(specifier, context, next) {
  try {
    return await next(specifier, context)
  } catch (err) {
    if (err.code === 'ERR_MODULE_NOT_FOUND' && (specifier.startsWith('./') || specifier.startsWith('../'))) {
      return await next(specifier + '.js', context)
    }
    throw err
  }
}
