/**
 * 静态网格 API：共享几何捕获、显式临时批次及长期登记的世界渲染组。
 * capture 管理 CPU 几何；cache 管理临时几何生命周期；gpu 管理缓冲状态；
 * render 执行材质与着色器绘制；world 管理登记对象和策略准备。
 * 实现类及其方法保持 public，允许扩展。
 * 这套尚未发布的 API 不为旧 client.world 原型提供适配入口。
 */
package com.github.mcmodderanchor.simplebedrockmodel.v2.client.mesh;
