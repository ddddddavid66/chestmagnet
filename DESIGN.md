---
name: 宝箱磁铁
description: Minecraft Forge 原生游戏内设置界面
colors:
  panel: "#20242A"
  body: "#F3EAD4"
  gold: "#E8BD68"
  active-tab: "#FFAA00"
  muted: "#C9BDA6"
  border: "#B18C4D"
  divider: "#776345"
typography:
  body:
    fontFamily: "Minecraft 客户端原生字体"
rounded:
  square: "0px"
spacing:
  control-gap: "4px"
  divider-inset: "8px"
  content-inset: "12px"
  axis-row: "25px"
  excluded-row: "29px"
components:
  native-button:
    height: "20px"
  radius-input:
    width: "54px"
    height: "20px"
---

# 游戏内设置界面

## Overview

**Creative North Star: "原生 Minecraft 像素工具面板"**

原生 Minecraft Screen，Operate 模式。像素直角边框、深灰背景、米白正文，金色强调标题与当前页。两页：设置、排除容器。按钮采用原生 Button，键盘焦点与禁用状态由游戏提供。小窗口自适应宽度，列表分页，不显示无意义动画。

**Key Characteristics:**
- 原生像素界面，不转换为网页或移动端样式。
- 配置操作紧凑直接，排除记录按页管理。
- 只让当前页标签使用选中金色。

## Colors

以深灰和暖色文字形成层次；具体色值以前置 tokens 为准。

### Primary
- `gold`：标题、范围摘要和配置页状态。
- `active-tab`：原生 `ChatFormatting.GOLD`，仅用于当前页标签；不同于标题金色。

### Neutral
- `panel`：面板底色；`body`：字段标签和列表正文。
- `muted`：说明、页码和右上页名。
- `border`：外框；`divider`：标题下分隔线。

## Typography

使用客户端提供的 `font`，随 Minecraft 字体资源与 GUI 缩放渲染。标题与正文没有额外字号缩放；层级由位置和颜色区分。按钮文字、阴影及输入框字体沿用原生控件，不另造字体系统。

## Layout

尺寸均为 Minecraft GUI 逻辑像素，不是截图物理像素。居中面板宽 `min(350, width - 12)`，高 `min(230, height - 8)`；外框向四边各延伸 2。标题起点为 `(12, 9)`，分隔线位于 y=22、高 1。

顶部导航 y=27；设置页操作 y=55，三轴控件从 y=88 开始，使用 `axis-row` 间距。排除列表每页最多 4 条，使用 `excluded-row` 间距，分页操作 y=179。控件高度与常用间距见 tokens。当前自适应是面板限宽/限高，不承诺极小窗口的完整重排。

## Elevation & Depth

面板为平色底、像素外框与分隔线，没有自定义投影或玻璃效果。Button 的凹凸纹理及交互状态由游戏绘制；不模拟网页卡片层级。

## Shapes

直角矩形与像素对齐；不增加圆角、胶囊或装饰性图标。

## Components

- **导航**：设置、带数量的排除容器、完成；切页后仅当前页标签金色，非当前页保留原生默认文字。
- **按钮**：原生 `Button`，保留游戏的悬停、键盘焦点与禁用表现。
- **半径输入**：原生 `EditBox`，三轴各配减/加按钮，只接受至多 3 位数字；通过“应用范围”提交。
- **排除记录**：每页最多 4 条，逐条移除；空列表说明添加方式；清空全部需要二次确认。

## Do's and Don'ts

### Do:
- **Do** 沿用原生字体、Button、EditBox 和键盘焦点。
- **Do** 用当前页标签金色区分导航状态，保留标题金色的独立色值。
- **Do** 保留分页、空状态说明和清空确认。

### Don't:
- **Don't** 给非当前页标签加选中金色。
- **Don't** 引入网页/iOS 控件、圆角卡片或无意义动画。
- **Don't** 将截图物理像素当作 GUI 逻辑尺寸。

## 结构确认页（1.1.0）
独立原生 Screen，最大540×360逻辑像素，自适应窗口。上方标题与全高度范围警告，中部512×512离屏真实方块预览，下方任务进度与确认/模式/取消。模型缓存为VertexBuffer，不逐帧重建。扫描区域独立于模型裁剪区域；箱子等动态方块用静态金色标记。局部自然神庙快照截图完成视觉审核，结论只覆盖可见表现。

## 1.2.7 最近获取物品

沿用直角像素面板、暖白正文与金色选中态。五页导航按标签字宽分配，窄窗口自动换行。范围页先显示半径和独立的应用按钮，下方用金色小标题区分收取来源。最近获取页在分类与刷新下方绘制可滚动单列列表：左侧原生物品图标，中间名称／目的地与时间，右侧对齐数量；固定底栏保留翻页和完成。附魔、长名称和背包处理说明只在悬停时显示。记录仅供回看，不代表库存。
