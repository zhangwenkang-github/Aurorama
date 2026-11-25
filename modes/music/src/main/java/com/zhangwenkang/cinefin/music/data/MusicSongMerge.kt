package com.zhangwenkang.cinefin.music.data

/**
 * W3-T20：把「服务端最新快照」合并进当前曲目。
 *
 * 只覆盖服务端确实给出的字段（名称 / 专辑 / 艺人 / 曲序 / 时长 / 图片 / 年份 / 流派 / 碟号 / 入库时间）； 服务端身份（itemId / serverId /
 * userId / trackRef）与本地专属字段（SAF URI / 本地库 id / 歌单条目身份）保持原值， 避免一次刷新把播放上下文或本地可播地址冲掉。
 *
 * 收藏态只做「升级」（服务端为 true 才覆盖）：部分读取接口不带 UserData，不能因此把本地已收藏误清掉。
 */
internal fun MusicSong.mergedWithFresh(fresh: MusicSong): MusicSong =
    copy(
        name = fresh.name.takeIf { it.isNotBlank() } ?: name,
        albumName = fresh.albumName.takeIf { it.isNotBlank() } ?: albumName,
        artist = fresh.artist ?: artist,
        albumArtist = fresh.albumArtist ?: albumArtist,
        indexNumber = fresh.indexNumber ?: indexNumber,
        runtimeTicks = fresh.runtimeTicks.takeIf { it > 0L } ?: runtimeTicks,
        imageUri = fresh.imageUri ?: imageUri,
        isFavorite = isFavorite || fresh.isFavorite,
        year = fresh.year ?: year,
        genre = fresh.genre ?: genre,
        discNumber = fresh.discNumber ?: discNumber,
        dateAddedMillis = fresh.dateAddedMillis ?: dateAddedMillis,
    )
