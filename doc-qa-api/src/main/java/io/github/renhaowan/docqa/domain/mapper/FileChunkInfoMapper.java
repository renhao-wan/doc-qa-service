package io.github.renhaowan.docqa.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.renhaowan.docqa.domain.dos.FileChunkInfoDO;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

/**
 * @Author: Renhao-Wan
 * @Date: 2025/8/11 11:36
 * @Version: v1.0.0
 * @Description: 分片信息表
 **/
public interface FileChunkInfoMapper extends BaseMapper<FileChunkInfoDO> {

    /**
     * 根据文件 MD5 值查询所有已上传的分片
     * @param fileMd5
     * @return
     */
    default List<FileChunkInfoDO> selecChunkedtList(String fileMd5) {
        return selectList(
                Wrappers.<FileChunkInfoDO>lambdaQuery()
                        .eq(FileChunkInfoDO::getFileMd5, fileMd5)
                        .orderByAsc(FileChunkInfoDO::getChunkNumber)
        );
    }

    /**
     * 查询指定分片是否已被上传
     * @param fileMd5
     * @param chunkNum
     * @return
     */
    default Long selectCountByMd5AndChunkNum(String fileMd5, Integer chunkNum) {
        return selectCount(
                Wrappers.<FileChunkInfoDO>lambdaQuery()
                        .eq(FileChunkInfoDO::getFileMd5, fileMd5)
                        .eq(FileChunkInfoDO::getChunkNumber, chunkNum)
        );
    }

    /**
     * 幂等写入分片记录：若 (file_md5, chunk_number) 已存在，则什么都不做
     * <p>
     * 为什么不沿用「先 selectCount 再 insert」：前端是 {@code MAX_CONCURRENT = 3} 的并发上传，
     * 检查与写入之间存在竞态窗口，两个线程可能同时通过检查、然后都插入。
     * <p>
     * 为什么不是「insert 后捕获 DuplicateKeyException」：PostgreSQL 中语句一旦违反约束，
     * 整个事务立即进入 aborted 状态，后续任何语句都会报
     * "current transaction is aborted, commands ignored until end of transaction block"。
     * 而 {@code CustomerServiceImpl#uploadChunk} 是 {@code @Transactional} 的，
     * 在方法内部 catch 住异常也无法让事务复活。用 ON CONFLICT 从根上不产生异常。
     *
     * @param chunkInfo 分片记录
     * @return 影响行数：1 = 确实是新分片，0 = 该分片已存在（并发重复提交）
     */
    @Insert("""
            INSERT INTO t_file_chunk_info (file_md5, chunk_number, chunk_name, chunk_size, create_time)
            VALUES (#{fileMd5}, #{chunkNumber}, #{chunkName}, #{chunkSize}, #{createTime})
            ON CONFLICT (file_md5, chunk_number) DO NOTHING
            """)
    int insertChunkIgnoreDuplicate(FileChunkInfoDO chunkInfo);

    /**
     * 根据文件 MD5 删除记录
     * @param fileMd5
     * @return
     */
    default int deleteByMd5(String fileMd5) {
        return delete(Wrappers.<FileChunkInfoDO>lambdaQuery()
                .eq(FileChunkInfoDO::getFileMd5, fileMd5));
    }


}
