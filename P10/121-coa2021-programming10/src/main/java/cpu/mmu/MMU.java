package cpu.mmu;

import memory.Memory;
import util.Transformer;

import javax.sound.sampled.Line;

/**
 * MMU内存管理单元抽象类
 * 接收一个48-bits的逻辑地址，并最终将其转换成32-bits的物理地址
 * Memory.SEGMENT和Memory.PAGE标志用于表示是否开启分段和分页。
 * 实际上在机器里从实模式进入保护模式后分段一定会保持开启(即使实模式也会使用段寄存器)，因此一共只要实现三种模式的内存管理即可：实模式、只有分段、段页式
 * 所有模式的物理地址都采用32-bits，实模式的物理地址高位补0
 */

public class MMU {

    private static final MMU mmuInstance = new MMU();

    private MMU() {
    }

    public static MMU getMMU() {
        return mmuInstance;
    }

    private final Memory memory = Memory.getMemory();

    private final Transformer transformer = new Transformer();

    /**
     * 读取数据
     *
     * @param logicAddr 48-bits逻辑地址
     * @param length    读取数据的长度
     * @return 内存中的数据
     */
    public char[] read(String logicAddr, int length) {
        String physicalAddr = addressTranslation(logicAddr, length);
        return memory.read(physicalAddr, length);
    }

    /**
     * 写数据
     *
     * @param logicAddr 48-bits逻辑地址
     * @param length    读取数据的长度
     */
    public void write(String logicAddr, int length, char[] data) {
        String physicalAddr = addressTranslation(logicAddr, length);
        memory.write(physicalAddr, length, data);
    }

    /**
     * 地址转换
     *
     * @param logicAddr 48-bits逻辑地址
     * @param length    读取数据的长度
     * @return 32-bits物理地址
     */
    private String addressTranslation(String logicAddr, int length) {
        String linearAddr;      // 32位线性地址
        String physicalAddr;    // 32位物理地址
        if (!Memory.SEGMENT) {   //实地址模式
            // 实模式：线性地址等于物理地址
            linearAddr = toRealLinearAddr(logicAddr);
            memory.real_load(linearAddr, length);  // 从磁盘中加载到内存
            physicalAddr = linearAddr;
        } else {
            // 分段模式
            int segIndex = getSegIndex(logicAddr);
            if (!memory.isValidSegDes(segIndex)) {
                // 缺段中断，该段不在内存中，内存从磁盘加载该段索引的数据
                memory.seg_load(segIndex);
            }
            linearAddr = toSegLinearAddr(logicAddr);
            // 权限检查
            int start = Integer.parseInt(transformer.binaryToInt(linearAddr));
            int base = chars2int(memory.getBaseOfSegDes(segIndex));
            int limit = chars2int(memory.getLimitOfSegDes(segIndex));
            //System.out.println(start+" "+base+" "+limit);
            if ((start < base) || (start + length > base + limit)) {
                throw new SecurityException("Segmentation Fault");
            }
            if (!Memory.PAGE) {
                // 分段模式：线性地址等于物理地址
                physicalAddr = linearAddr;
            } else {
                // 段页式
                int startvPageNo = Integer.parseInt(transformer.binaryToInt(linearAddr.substring(0, 20)));   // 高20位表示虚拟页号
                int offset = Integer.parseInt(transformer.binaryToInt(linearAddr.substring(20, 32)));         // 低12位的页内偏移
                int pages = (length - offset + Memory.PAGE_SIZE_B - 1) / Memory.PAGE_SIZE_B;
                if (offset > 0) pages++;
                int endvPageNo = startvPageNo + pages - 1;
                for (int i = startvPageNo; i <= endvPageNo; i++) {
                    if (!memory.isValidPage(i)) {
                        // 缺页中断，该页不在内存中，内存从磁盘加载该页的数据
                        memory.page_load(i);
                    }
                }
                physicalAddr = toPagePhysicalAddr(linearAddr);
            }
        }
        return physicalAddr;
    }

    /**
     * 实模式下的逻辑地址转线性地址
     *
     * @param logicAddr 48位 = 16位段寄存器 + 32位offset，计算公式为：①(16-bits段寄存器左移4位 + offset的低16-bits) = 20-bits物理地址 ②高位补0到32-bits
     * @return 32-bits实模式线性地址
     */
    public String toRealLinearAddr(String logicAddr) {
        // TODO
        //段寄存器
        String SegRegister = logicAddr.substring(0,16);
        //offset
        String offset = logicAddr.substring(16);
        //线性地址，高位已补0
        StringBuffer linerAddress = new StringBuffer();
        linerAddress.append("000000000000");
        //段寄存器左移4位
        StringBuffer phyAddr = new StringBuffer();
        phyAddr.append(SegRegister).append("0000");
        //段寄存器和offset低16位相加
        linerAddress.append(adder(phyAddr.toString(),offset.substring(16)));
        return linerAddress.toString();
    }

    private String adder(String src, String dst)
    {
        int len = Math.max(src.length(),dst.length());
        int[] src_arr = transformer.StringToArray(src,len);
        int[] dst_arr = transformer.StringToArray(dst,len);
        int carry = 0;
        int[] res = new int[len];
        for(int i = len - 1; i >= 0; --i)
        {
            res[i] = src_arr[i] ^ dst_arr[i] ^ carry;
            carry  = (src_arr[i] & dst_arr[i]) | (src_arr[i] & carry) | (dst_arr[i] & carry);
        }
        return transformer.ArrayToString(res);
    }

    /**
     * 分段模式下的逻辑地址转线性地址
     *
     * @param logicAddr 48位 = 16位段选择符(高13位index选择段表项) + 32位段内偏移
     * @return 32-bits 线性地址
     */
    private String toSegLinearAddr(String logicAddr) {
        // TODO
        //if(Memory.PAGE) return logicAddr.substring(16);
        int segIndex = getSegIndex(logicAddr);
        String offset = logicAddr.substring(16);
        String base = transformer.ArrayToString(memory.getBaseOfSegDes(segIndex));
        String linearAddress = adder(base,offset);
        return linearAddress;
    }

    /**
     * 段页式下的线性地址转物理地址
     *
     * @param linearAddr 32位
     * @return 32-bits 物理地址
     */
    private String toPagePhysicalAddr(String linearAddr) {
        // TODO
        String vPage = linearAddr.substring(0,20);
        String offset = linearAddr.substring(20);
        int pageIndex = Integer.parseInt(transformer.binaryToInt(vPage));
        String pPage = transformer.ArrayToString(memory.getFrameOfPage(pageIndex));
        return pPage+offset;
    }

    /**
     * 根据逻辑地址找到对应的段索引
     *
     * @param logicAddr 逻辑地址
     * @return 整数表示的段索引
     */
    private int getSegIndex(String logicAddr) {
        String indexStr = logicAddr.substring(0, 13);
        return Integer.parseInt(transformer.binaryToInt(indexStr));   // 段描述符索引
    }

    private int chars2int(char[] chars) {
        return Integer.parseInt(transformer.binaryToInt(String.valueOf(chars)));
    }

    public static void main(String[] args)
    {
        System.out.append(getMMU().toSegLinearAddr("000000000000000000000000000000000000000000000000"));
    }

}
