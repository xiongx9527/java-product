export interface SelectOption {
  label: string
  value: string | number
  i18nKey?: string
}

export interface ModelTypeOption extends SelectOption {
  value: number
  // 该类型支持的「上游格式」；embedding 为空数组表示不参与上游格式选择
  supportedFormats?: string[]
}

export const modelTypeOptions: ModelTypeOption[] = [
  {
    label: '大语言模型',
    value: 0,
    i18nKey: 'modelType.llm',
    supportedFormats: ['chat_completions', 'azure', 'anthropic_messages', 'responses'],
  },
  { label: '向量模型', value: 1, i18nKey: 'modelType.embedding', supportedFormats: [] },
  { label: '重排模型', value: 2, i18nKey: 'modelType.rerank', supportedFormats: [] },
]

export const getModelTypeName = (value: any) => {
  const tv = parseInt(value)
  const item = modelTypeOptions.find((item) => item.value === tv)
  return item?.i18nKey || item?.label || ''
}
